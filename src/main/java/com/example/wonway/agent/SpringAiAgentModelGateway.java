package com.example.wonway.agent;

import com.example.wonway.agent.exception.AgentUnavailableException;
import com.example.wonway.agent.model.GeneratedConsultation;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/** 메모리 없는 전용 ChatClient에 현재 요청의 도구만 등록합니다. */
@Component
class SpringAiAgentModelGateway implements AgentModelGateway {

    private static final String SYSTEM_PROMPT = """
            당신은 우리WON 금융 상담 에이전트다.
            고객 상황은 현재 사용자 메시지에서만 추출하고 이전 요청이나 응답은 사용하지 않는다.
            현재 메시지만으로 업무 intent를 식별할 수 없으면 도구를 호출하지 말고
            intentClarificationNeeded를 true로 반환하여 업무와 조건을 함께 적어 달라고 질문한다.
            업무 intent를 식별할 수 있으면 intentClarificationNeeded를 false로 두고
            반드시 먼저 findApplicablePolicies 도구를 호출한다.
            정책 결과가 MATCHED이면 rankConsultationChannels 도구도 호출한다.
            현재 메시지에 없는 사실은 null 또는 누락으로 두며 false로 추측하지 않는다.
            requiresIdentityVerification은 정책 필터이며 고객의 인증 가능 여부와 다르다.
            requestGoal은 GENERAL_GUIDANCE, SELF_SERVICE_GUIDANCE, ACCOUNT_SPECIFIC_CHECK,
            EXECUTION_REQUEST 중 하나만 사용한다.
            정책 근거는 정책 도구 결과, 채널 지원·점수·순위·URL은 채널 도구 결과만 사용한다.
            NEEDS_INFORMATION이면 답을 바꾸는 조건을 현재 업무와 함께 다시 적어 달라고 안내한다.
            REVIEW_REQUIRED이면 근거 확인이 필요하다고 안내하고 빈 절차를 지식으로 채우지 않는다.
            실제 금융 업무를 실행했거나 상담원 연결을 완료했다고 표현하지 않는다.
            비밀번호, OTP, 주민등록번호, 계좌번호 등 민감정보를 요청하거나 요약에 포함하지 않는다.
            consultationSummary에는 사용자 원문을 복사하지 말고 상담 목적과 안내 결과만 적는다.

            아래 JSON은 허용된 intent/product/facts 코드 참고 자료다. 메시지에 없는 값은 사용하지 않는다.
            <tool-input-catalog>
            %s
            </tool-input-catalog>
            """;

    private final Supplier<ChatModel> chatModelSupplier;
    private final String systemPrompt;

    @Autowired
    SpringAiAgentModelGateway(ObjectProvider<ChatModel> chatModels) {
        this((Supplier<ChatModel>) chatModels::getIfAvailable);
    }

    SpringAiAgentModelGateway(ChatModel chatModel) {
        this(() -> chatModel);
    }

    private SpringAiAgentModelGateway(Supplier<ChatModel> chatModelSupplier) {
        this.chatModelSupplier = chatModelSupplier;
        this.systemPrompt = SYSTEM_PROMPT.formatted(AgentPromptCatalog.load());
    }

    @Override
    public GeneratedConsultation generate(String message, ConsultationTools tools) {
        ChatModel chatModel = chatModelSupplier.get();
        if (chatModel == null) {
            throw new AgentUnavailableException("사용 가능한 Spring AI ChatModel이 없습니다.");
        }

        // 요청마다 메모리 없는 Client를 생성해 이전 요청의 메시지나 도구 결과를 이어받지 않는다.
        return ChatClient.builder(chatModel).build().prompt()
                .system(systemPrompt)
                .user(message)
                .tools(tools)
                .call()
                .entity(GeneratedConsultation.class);
    }
}
