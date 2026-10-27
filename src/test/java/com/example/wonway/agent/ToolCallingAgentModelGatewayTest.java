package com.example.wonway.agent;

import com.example.wonway.agent.exception.AgentUnavailableException;
import com.example.wonway.agent.model.GeneratedConsultation;
import com.example.wonway.agent.model.ResponseType;
import com.example.wonway.common.contract.ChannelRecommendation;
import com.example.wonway.common.contract.ChannelType;
import com.example.wonway.policy.model.ChannelEvaluation;
import com.example.wonway.policy.model.PolicyEvaluation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.example.wonway.agent.ConsultationToolsTest.*;
import static org.assertj.core.api.Assertions.*;

/** Scripted LLM responses exercise the production gateway and real Spring AI tool execution loop. */
class ToolCallingAgentModelGatewayTest {
    static final ObjectMapper JSON = new ObjectMapper();

    @Test void preservesOpenAiOptionsTypeAndConfiguredDefaultsWithoutMutatingTheModel() {
        var defaults = org.springframework.ai.openai.OpenAiChatOptions.builder()
                .model("configured-test-model").temperature(0.3).build();
        ChatModel provider = new ChatModel() {
            @Override public org.springframework.ai.chat.prompt.ChatOptions getOptions() { return defaults; }
            @Override public ChatResponse call(Prompt prompt) {
                // The real OpenAiChatModel performs this cast before it sends any HTTP request.
                var actual = (org.springframework.ai.openai.OpenAiChatOptions) prompt.getOptions();
                assertThat(actual.getModel()).isEqualTo("configured-test-model");
                assertThat(actual.getTemperature()).isEqualTo(0.3);
                assertThat(actual.getToolCallbacks()).hasSize(3);
                return finish(true, "어떤 업무가 궁금하신가요?");
            }
        };
        new ToolCallingAgentModelGateway(provider).generate("문의", List.of(), tools());
        assertThat(defaults.getToolCallbacks()).isNullOrEmpty();
    }

    @Test void allowsModelToChooseClarificationWithoutToolsAndIncludesConversationHistory() {
        var model = new QueueModel(finish(true, "어떤 업무인가요?"));
        var gateway = new ToolCallingAgentModelGateway(model);
        var result = gateway.generate("이거 어떻게 해?", List.of(new ConversationMemory.Turn("안녕", "무엇을 도와드릴까요?")), tools());
        assertThat(result.intentClarificationNeeded()).isTrue();
        assertThat(model.prompts.getFirst().getInstructions().subList(1, 4)).extracting(Message::getText)
                .containsExactly("안녕", "무엇을 도와드릴까요?", "이거 어떻게 해?");
        var options = (ToolCallingChatOptions) model.prompts.getFirst().getOptions();
        assertThat(options.getToolCallbacks()).extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyInAnyOrder("updateConsultation", "findApplicablePolicies", "rankConsultationChannels");
        assertThat(model.prompts).hasSize(1);
    }

    @Test void executesOnlyModelRequestedToolsFeedsResultsBackAndUsesVerifiedRecommendation() {
        var model = new QueueModel(
                update("CARD_LOSS", "카드 분실", List.of(), List.of(), "카드 분실 안내"),
                tool("findApplicablePolicies", "{}"),
                tool("rankConsultationChannels", "{\"requestGoal\":\"GENERAL_GUIDANCE\"}"),
                finish(false, "계좌 변경까지 완료했어요"));
        var memory = new ConversationMemory();
        var id = UUID.randomUUID(); memory.open(id);
        var service = new SpringAiAgentService(new ToolCallingAgentModelGateway(model),
                new StubPolicyService(MATCHED), new StubChannelRankingService(rankedEvaluation()), memory);
        var result = service.chat(id, "카드 분실 안내가 필요해");
        assertThat(result.responseType()).isEqualTo(ResponseType.CHANNEL_RECOMMENDATION);
        assertThat(result.recommendations()).extracting(ChannelRecommendation::score).containsExactly(77);
        assertThat(result.answer()).contains("채널을 선택").doesNotContain("변경까지 완료");
        assertThat(result.consultationSummary()).isEqualTo("카드 분실 안내");
        assertThat(model.prompts).hasSize(4);
        assertThat(model.prompts.get(2).getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast).flatMap(m -> m.getResponses().stream()).map(ToolResponseMessage.ToolResponse::responseData))
                .anyMatch(content -> content.contains("MATCHED"));
    }

    @Test void modelCollectsMissingFactsAcrossTurnsThenSelectsRankingAndPreservesMenuAndGuidance() {
        var model = new QueueModel(
                update("AUTO_TRANSFER_MANAGEMENT", "생활비 날짜 변경", List.of(slot("operation", "CHANGE_DATE", "날짜 변경")), List.of(), "생활비 날짜 변경"),
                tool("findApplicablePolicies", "{}"), finish(true, "받는 계좌가 우리은행인가요, 다른 은행인가요?"),
                update(null, null, List.of(slot("transferType", "INTERBANK", "타행")), List.of(), null),
                tool("findApplicablePolicies", "{}"), tool("rankConsultationChannels", "{\"requestGoal\":\"SELF_SERVICE_GUIDANCE\"}"),
                finish(true, "앱을 이용할 수 있나요?"),
                update(null, null, List.of(), List.of(slot("selfServiceAvailable", "true", "앱 이용 가능")), null),
                tool("findApplicablePolicies", "{}"), tool("rankConsultationChannels", "{\"requestGoal\":\"SELF_SERVICE_GUIDANCE\"}"),
                finish(false, "채널을 선택하세요"));
        var branch = new PolicyEvaluation.Branch("INTERBANK_AUTO_GUIDE", "APPLICABLE", List.of(),
                List.of("공식 메뉴에서 등록 내역을 선택하세요."), List.of("적용 회차를 확인하세요."), List.of(), List.of(), "WEB_CHECKED", "WOORI_BANK");
        var policy = new PolicyEvaluation(PolicyEvaluation.Status.MATCHED,
                List.of(new PolicyEvaluation.Match(POLICY, List.of(branch), "DEMO_ONLY")), List.of(), null);
        AtomicInteger rankingCalls = new AtomicInteger();
        var channels = new StubChannelRankingService(rankedEvaluation()) {
            @Override public ChannelEvaluation evaluate(com.example.wonway.common.contract.ChannelRankingCondition condition) {
                rankingCalls.incrementAndGet();
                assertThat(condition.facts()).containsEntry("transferType", "INTERBANK").containsEntry("operation", "CHANGE_DATE");
                assertThat(condition.context().selfServiceAvailable()).isTrue();
                assertThat(condition.context().urgent()).isNull();
                assertThat(condition.context().needsAccountSpecificCheck()).isNull();
                return new ChannelEvaluation("RANKED", List.of(new ChannelRecommendation(ChannelType.CHATBOT,
                        87, true, List.of("검증된 근거"), null)), List.of(), "test");
            }
        };
        var memory = new ConversationMemory(); var id = UUID.randomUUID(); memory.open(id);
        var service = new SpringAiAgentService(new ToolCallingAgentModelGateway(model), new StubPolicyService(policy), channels, memory);
        var first = service.chat(id, "생활비 날짜 변경하고 싶어");
        assertThat(first.responseType()).isEqualTo(ResponseType.FOLLOW_UP);
        assertThat(first.recommendations()).isEmpty(); assertThat(first.guidance()).isNull();
        var second = service.chat(id, "타행이야");
        assertThat(second.responseType()).isEqualTo(ResponseType.FOLLOW_UP);
        assertThat(second.answer()).contains("앱"); assertThat(rankingCalls).hasValue(0);
        var last = service.chat(id, "앱 이용 가능");
        assertThat(last.responseType()).isEqualTo(ResponseType.CHANNEL_RECOMMENDATION);
        assertThat(last.guidance()).contains("공식 메뉴", "적용 회차");
        assertThat(last.actions()).extracting(action -> action.menuId()).containsExactly("AUTO_TRANSFER");
        assertThat(last.consultationSummary()).isEqualTo("생활비 날짜 변경");
        assertThat(rankingCalls).hasValue(1);
    }

    @Test void refusesFinalRecommendationWithoutCurrentPolicyOrChannelResults() {
        var noPolicy = new QueueModel(update("CARD_LOSS", "카드 분실", List.of(), List.of(), "카드 분실"), finish(false, "추천합니다"));
        assertThatThrownBy(() -> new ToolCallingAgentModelGateway(noPolicy).generate("카드 분실", List.of(), tools()))
                .isInstanceOf(AgentUnavailableException.class).hasMessageContaining("정책 조회");
        var noChannels = new QueueModel(update("CARD_LOSS", "카드 분실", List.of(), List.of(), "카드 분실"),
                tool("findApplicablePolicies", "{}"), finish(false, "추천합니다"));
        assertThatThrownBy(() -> new ToolCallingAgentModelGateway(noChannels).generate("카드 분실", List.of(), tools()))
                .isInstanceOf(AgentUnavailableException.class).hasMessageContaining("채널 평가");
    }

    @Test void rejectsUnknownToolsMalformedOutputAndUnavailableModel() {
        assertThatThrownBy(() -> new ToolCallingAgentModelGateway(new QueueModel(tool("executeBankTransfer", "{}")))
                .generate("문의", List.of(), tools())).isInstanceOf(AgentUnavailableException.class).hasMessageContaining("허용되지 않은");
        assertThatThrownBy(() -> new ToolCallingAgentModelGateway(new QueueModel(text("not-json")))
                .generate("문의", List.of(), tools())).isInstanceOf(AgentUnavailableException.class).hasMessageContaining("형식");
        assertThatThrownBy(() -> new ToolCallingAgentModelGateway((ChatModel) null)
                .generate("문의", List.of(), tools())).isInstanceOf(AgentUnavailableException.class).hasMessageContaining("ChatModel");
    }

    @Test void boundsRepeatedModelToolCalls() {
        ChatResponse[] repeated = new ChatResponse[ToolCallingAgentModelGateway.MAX_MODEL_CALLS];
        Arrays.fill(repeated, tool("findApplicablePolicies", "{}"));
        var model = new QueueModel(repeated);
        assertThatThrownBy(() -> new ToolCallingAgentModelGateway(model).generate("문의", List.of(), tools()))
                .isInstanceOf(AgentUnavailableException.class).hasMessageContaining("횟수 제한");
        assertThat(model.prompts).hasSize(ToolCallingAgentModelGateway.MAX_MODEL_CALLS);
    }

    @Test void reportsRejectedPreconditionToModelSoItCanChooseTheCorrectNextAction() {
        var model = new QueueModel(tool("rankConsultationChannels", "{\"requestGoal\":\"GENERAL_GUIDANCE\"}"),
                update("CARD_LOSS", "카드 분실", List.of(), List.of(), "카드 분실"),
                tool("findApplicablePolicies", "{}"),
                tool("rankConsultationChannels", "{\"requestGoal\":\"GENERAL_GUIDANCE\"}"), finish(false, "선택하세요"));
        var tools = tools();
        new ToolCallingAgentModelGateway(model).generate("카드 분실", List.of(), tools);
        assertThat(tools.verifiedRecommendations()).hasSize(1);
        assertThat(model.prompts.get(1).getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast).flatMap(m -> m.getResponses().stream()).map(ToolResponseMessage.ToolResponse::responseData))
                .anyMatch(content -> content.contains("REJECTED"));
    }

    @Test void rejectsOversizedToolBatchBeforeExecutingAnyCalls() {
        var calls = java.util.stream.IntStream.range(0, ToolCallingAgentModelGateway.MAX_TOOL_CALLS + 1)
                .mapToObj(i -> new AssistantMessage.ToolCall("batch-" + i, "function", "findApplicablePolicies", "{}")).toList();
        var model = new QueueModel(new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(calls).build()))));
        assertThatThrownBy(() -> new ToolCallingAgentModelGateway(model).generate("문의", List.of(), tools()))
                .isInstanceOf(AgentUnavailableException.class).hasMessageContaining("횟수 제한");
        assertThat(model.prompts).hasSize(1);
    }

    static ConsultationTools tools() { return new ConsultationTools(new StubPolicyService(MATCHED), new StubChannelRankingService(rankedEvaluation())); }
    static ConsultationUnderstanding.Slot slot(String name, String value, String evidence) { return new ConsultationUnderstanding.Slot(name, value, evidence); }
    static ChatResponse update(String intent, String evidence, List<ConsultationUnderstanding.Slot> facts,
                               List<ConsultationUnderstanding.Slot> context, String purpose) {
        return tool("updateConsultation", json(Map.of("extraction", new ConsultationUnderstanding.Extraction(intent, evidence, false, facts, context, purpose))));
    }
    static ChatResponse tool(String name, String args) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(UUID.randomUUID().toString(), "function", name, args))).build())));
    }
    static ChatResponse finish(boolean question, String answer) { return text(json(new GeneratedConsultation(answer, "문의 안내", question))); }
    static ChatResponse text(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
    static String json(Object value) {
        try { return JSON.writeValueAsString(value); } catch (Exception exception) { throw new AssertionError(exception); }
    }
    static final class QueueModel implements ChatModel {
        private final Queue<ChatResponse> responses;
        final List<Prompt> prompts = new ArrayList<>();
        QueueModel(ChatResponse... responses) { this.responses = new ArrayDeque<>(List.of(responses)); }
        @Override public ChatResponse call(Prompt prompt) { prompts.add(prompt); return responses.remove(); }
    }
}
