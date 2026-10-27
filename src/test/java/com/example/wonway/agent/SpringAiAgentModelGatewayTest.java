package com.example.wonway.agent;

import com.example.wonway.agent.model.GeneratedConsultation;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.ArrayList;

import static com.example.wonway.agent.ConsultationToolsTest.MATCHED;
import static com.example.wonway.agent.ConsultationToolsTest.StubChannelRankingService;
import static com.example.wonway.agent.ConsultationToolsTest.StubPolicyService;
import static com.example.wonway.agent.ConsultationToolsTest.rankedEvaluation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringAiAgentModelGatewayTest {

    @Test
    void sendsPriorUserAndAssistantMessagesBeforeLatestFollowUp() {
        QueueChatModel model = new QueueChatModel(textResponse("""
                {"answer":"26일 변경 방법을 확인할게요.","consultationSummary":"자동이체 변경 안내","intentClarificationNeeded":false}
                """));
        var gateway = new SpringAiAgentModelGateway(model);
        var tools = new ConsultationTools(new StubPolicyService(MATCHED),
                new StubChannelRankingService(rankedEvaluation()));
        gateway.generate("26일이요", List.of(new ConversationMemory.Turn("자동이체 날짜를 바꿀래요", "며칠로 변경할까요?")), tools);
        assertThat(model.prompts.get(0).getInstructions().subList(1, 3)).extracting(Message::getText)
                .containsExactly("자동이체 날짜를 바꿀래요", "며칠로 변경할까요?");
        // Spring AI appends the structured-output schema to the latest user message.
        assertThat(model.prompts.get(0).getInstructions().get(3).getText()).startsWith("26일이요");
        assertThat(model.prompts.get(0).getInstructions().get(1)).isInstanceOf(org.springframework.ai.chat.messages.UserMessage.class);
        assertThat(model.prompts.get(0).getInstructions().get(2)).isInstanceOf(AssistantMessage.class);
    }

    @Test
    void executesSpringAiToolCallsAndParsesFinalStructuredOutput() {
        ChatResponse policyCall = toolCall("call-policy", "findApplicablePolicies", """
                        {"intent":"CARD_LOSS","customerType":"INDIVIDUAL","productType":"CARD",
                         "facts":{"issuer":"WOORI_CARD","requestKind":"REPORT_LOSS"}}
                        """);
        assertThat(policyCall.hasToolCalls()).isTrue();
        QueueChatModel chatModel = new QueueChatModel(
                policyCall,
                toolCall("call-channel", "rankConsultationChannels", """
                        {"intent":"CARD_LOSS","customerType":"INDIVIDUAL","productType":"CARD",
                         "facts":{"issuer":"WOORI_CARD","requestKind":"REPORT_LOSS"},
                         "requestGoal":"GENERAL_GUIDANCE","urgent":true,"preferredChannel":"PHONE",
                         "identityVerificationAvailable":true}
                        """),
                textResponse("""
                        {"answer":"전화 상담을 이용할 수 있습니다.",
                         "consultationSummary":"카드 분실 신고 채널 안내",
                         "intentClarificationNeeded":false}
                        """)
        );
        ConsultationTools tools = new ConsultationTools(
                new StubPolicyService(MATCHED),
                new StubChannelRankingService(rankedEvaluation())
        );
        SpringAiAgentModelGateway gateway = new SpringAiAgentModelGateway(chatModel);

        GeneratedConsultation result;
        try {
            result = gateway.generate("우리카드를 잃어버려 신고하려고 해요", tools);
        } catch (RuntimeException exception) {
            assertThat(chatModel.callCount()).as("tool calling loop invocation count").isEqualTo(3);
            throw exception;
        }

        assertThat(result.answer()).isEqualTo("전화 상담을 이용할 수 있습니다.");
        assertThat(tools.lastPolicyEvaluation().status()).isEqualTo(MATCHED.status());
        assertThat(tools.lastChannelEvaluation().status()).isEqualTo("RANKED");
        assertThat(tools.verifiedRecommendations()).hasSize(1);
        assertThat(chatModel.callCount()).isEqualTo(3);
    }

    @Test
    void reportsExplicitAgentErrorWhenChatModelIsUnavailable() {
        SpringAiAgentModelGateway gateway = new SpringAiAgentModelGateway((ChatModel) null);
        ConsultationTools tools = new ConsultationTools(
                new StubPolicyService(MATCHED),
                new StubChannelRankingService(rankedEvaluation())
        );

        assertThatThrownBy(() -> gateway.generate("카드 문의", tools))
                .isInstanceOf(com.example.wonway.agent.exception.AgentUnavailableException.class)
                .hasMessageContaining("ChatModel");
    }

    private static ChatResponse toolCall(String id, String name, String arguments) {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(id, "function", name, arguments);
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(toolCall))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static ChatResponse textResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private static final class QueueChatModel implements ChatModel {
        private final Queue<ChatResponse> responses;
        private int callCount;
        private final List<Prompt> prompts = new ArrayList<>();

        private QueueChatModel(ChatResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            callCount++;
            prompts.add(prompt);
            return responses.remove();
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        private int callCount() {
            return callCount;
        }
    }
}
