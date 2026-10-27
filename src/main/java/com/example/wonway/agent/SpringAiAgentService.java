package com.example.wonway.agent;

import com.example.wonway.agent.exception.AgentUnavailableException;
import com.example.wonway.agent.model.AgentResult;
import com.example.wonway.agent.model.GeneratedConsultation;
import com.example.wonway.policy.model.ChannelEvaluation;
import com.example.wonway.policy.model.PolicyEvaluation;
import com.example.wonway.policy.service.ChannelRankingService;
import com.example.wonway.policy.service.PolicyService;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * 요청별 Spring AI 도구 실행 결과를 검증해 외부 API 응답으로 조합합니다.
 */
@Service
public class SpringAiAgentService implements AgentService {

    static final String UNSUPPORTED_MESSAGE = "지금은 지원하지 않는 기능입니다";
    private static final String EMPTY_ANSWER = "상담 안내를 생성하지 못했습니다. 현재 문의 업무와 조건을 함께 적어 다시 질문해 주세요.";
    private static final String EMPTY_SUMMARY = "현재 요청의 정책과 상담 채널을 확인함";

    private final AgentModelGateway modelGateway;
    private final PolicyService policyService;
    private final ChannelRankingService channelRankingService;

    SpringAiAgentService(
            AgentModelGateway modelGateway,
            PolicyService policyService,
            ChannelRankingService channelRankingService
    ) {
        this.modelGateway = modelGateway;
        this.policyService = policyService;
        this.channelRankingService = channelRankingService;
    }

    @Override
    public AgentResult chat(UUID sessionId, String message) {
        Objects.requireNonNull(sessionId, "sessionId는 필수입니다.");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("상담 메시지는 필수입니다.");
        }

        // 상태가 다른 고객에게 공유되지 않도록 도구 객체를 매 요청마다 새로 만든다.
        ConsultationTools tools = new ConsultationTools(policyService, channelRankingService);
        GeneratedConsultation generated = modelGateway.generate(message, tools);
        PolicyEvaluation policy = tools.lastPolicyEvaluation();
        ChannelEvaluation channels = tools.lastChannelEvaluation();

        if (policy == null) {
            if (generated != null && Boolean.TRUE.equals(generated.intentClarificationNeeded())) {
                return new AgentResult(
                        nonBlankOrDefault(generated.answer(),
                                "어떤 금융 업무에 대한 문의인지 현재 상황과 함께 조금 더 자세히 알려 주세요."),
                        java.util.List.of(),
                        nonBlankOrDefault(generated.consultationSummary(), "상담 업무 확인이 필요함")
                );
            }
            throw new AgentUnavailableException("AI가 필수 정책 조회 도구를 호출하지 않았습니다.");
        }
        if (policy.status() == PolicyEvaluation.Status.UNSUPPORTED) {
            return new AgentResult(UNSUPPORTED_MESSAGE, java.util.List.of(), "지원하지 않는 상담 요청");
        }
        if (policy.status() == PolicyEvaluation.Status.MATCHED && channels == null) {
            throw new AgentUnavailableException("AI가 필수 채널 평가 도구를 호출하지 않았습니다.");
        }

        String fallback = fallbackAnswer(policy, channels);
        return new AgentResult(
                nonBlankOrDefault(generated == null ? null : generated.answer(), fallback),
                tools.verifiedRecommendations(),
                nonBlankOrDefault(generated == null ? null : generated.consultationSummary(), EMPTY_SUMMARY)
        );
    }

    private static String fallbackAnswer(PolicyEvaluation policy, ChannelEvaluation channels) {
        if (policy.status() == PolicyEvaluation.Status.NEEDS_INFORMATION) {
            return "정확한 안내를 위해 현재 문의 업무와 필요한 조건을 함께 적어 주세요.";
        }
        if (policy.status() == PolicyEvaluation.Status.REVIEW_REQUIRED) {
            return "정책 근거와 적용 범위를 추가로 확인해야 합니다.";
        }
        if (channels == null) {
            return EMPTY_ANSWER;
        }
        return switch (channels.status()) {
            case "NEEDS_INFORMATION", "NEEDS_CONTEXT" ->
                    "상담 채널을 안내하려면 현재 문의 목적과 고객 상황을 조금 더 알려 주세요.";
            case "REVIEW_REQUIRED", "CONTACT_SCOPE_REVIEW_REQUIRED", "POLICY_REFERENCE_MISMATCH" ->
                    "정책 근거와 담당 기관을 추가로 확인해야 합니다.";
            case "NO_ELIGIBLE_CHANNEL" -> "현재 조건에서 검증된 상담 채널을 찾지 못했습니다.";
            case "EXECUTION_NOT_IMPLEMENTED" ->
                    "이 상담에서는 실제 금융 업무를 실행할 수 없습니다. 공식 절차를 확인해 주세요.";
            case "UNSUPPORTED" -> UNSUPPORTED_MESSAGE;
            default -> EMPTY_ANSWER;
        };
    }

    private static String nonBlankOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
