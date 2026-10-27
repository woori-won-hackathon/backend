package com.example.wonway.agent;

import com.example.wonway.agent.exception.AgentUnavailableException;
import com.example.wonway.agent.model.AgentResult;
import com.example.wonway.agent.model.GeneratedConsultation;
import com.example.wonway.agent.model.ResponseType;
import com.example.wonway.agent.model.BusinessAction;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(SpringAiAgentService.class);

    static final String UNSUPPORTED_MESSAGE = "지금은 지원하지 않는 기능입니다";
    private static final String EMPTY_ANSWER = "상담 안내를 생성하지 못했습니다. 현재 문의 업무와 조건을 함께 적어 다시 질문해 주세요.";
    private static final String EMPTY_SUMMARY = "현재 요청의 정책과 상담 채널을 확인함";

    private final AgentModelGateway modelGateway;
    private final PolicyService policyService;
    private final ChannelRankingService channelRankingService;
    private final ConversationMemory conversations;

    SpringAiAgentService(
            AgentModelGateway modelGateway,
            PolicyService policyService,
            ChannelRankingService channelRankingService,
            ConversationMemory conversations
    ) {
        this.modelGateway = modelGateway;
        this.policyService = policyService;
        this.channelRankingService = channelRankingService;
        this.conversations = conversations;
    }

    @Override
    public AgentResult chat(UUID sessionId, String message) {
        Objects.requireNonNull(sessionId, "sessionId는 필수입니다.");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("상담 메시지는 필수입니다.");
        }

        return conversations.respond(sessionId, message,
                history -> generateReply(sessionId, message, history), AgentResult::answer, AgentResult::understanding);
    }

    private AgentResult generateReply(UUID sessionId, String message, java.util.List<ConversationMemory.Turn> history) {
        // Reuse customer conversation, but recompute policy and channel decisions on every turn.
        ConsultationTools tools = new ConsultationTools(policyService, channelRankingService, sessionId);
        log.info("AI 응답 생성 시작 sessionId={} historyTurns={}", sessionId, history.size());
        GeneratedConsultation generated = modelGateway.generate(message, history, tools);
        if (generated == null) throw new AgentUnavailableException("상담 모델 응답이 비어 있습니다.");
        PolicyEvaluation policy = tools.lastPolicyEvaluation();
        ChannelEvaluation channels = tools.lastChannelEvaluation();

        // A question is a separate phase, including when policy is known but context is not.
        if (tools.understanding() != null && Boolean.TRUE.equals(generated.intentClarificationNeeded())) {
            return new AgentResult(generated.answer(), List.of(),
                    nonBlankOrDefault(tools.understanding().purpose(), "상담 업무 확인이 필요함"),
                    ResponseType.FOLLOW_UP, List.of(), null, tools.understanding());
        }

        if (policy == null) {
            if (generated != null && Boolean.TRUE.equals(generated.intentClarificationNeeded())) {
                return new AgentResult(
                        nonBlankOrDefault(generated.answer(),
                                "어떤 금융 업무에 대한 문의인지 현재 상황과 함께 조금 더 자세히 알려 주세요."),
                        java.util.List.of(),
                        nonBlankOrDefault(generated.consultationSummary(), "상담 업무 확인이 필요함"),
                        ResponseType.FOLLOW_UP, List.of()
                );
            }
            throw new AgentUnavailableException("AI가 필수 정책 조회 도구를 호출하지 않았습니다.");
        }
        if (policy.status() == PolicyEvaluation.Status.UNSUPPORTED) {
            return new AgentResult(UNSUPPORTED_MESSAGE, List.of(), "지원하지 않는 상담 요청",
                    ResponseType.UNRESOLVED, List.of(), null, tools.understanding());
        }
        if (policy.status() == PolicyEvaluation.Status.MATCHED && channels == null) {
            throw new AgentUnavailableException("AI가 필수 채널 평가 도구를 호출하지 않았습니다.");
        }

        String fallback = fallbackAnswer(policy, channels);
        ResponseType responseType = responseType(policy, channels);
        var recommendations = responseType == ResponseType.CHANNEL_RECOMMENDATION
                ? tools.verifiedRecommendations() : List.<com.example.wonway.common.contract.ChannelRecommendation>of();
        // Model-written questions are preserved above; recommendation prose cannot invent a result or execution.
        String answer = tools.understanding() != null
                ? responseType == ResponseType.CHANNEL_RECOMMENDATION
                    ? "확인된 조건으로 상담 방법을 비교했어요. 이용할 채널을 선택해 주세요." : fallback
                : nonBlankOrDefault(generated.answer(), fallback);
        return new AgentResult(
                answer,
                recommendations,
                nonBlankOrDefault(tools.understanding() == null ? generated.consultationSummary()
                        : tools.understanding().purpose(), EMPTY_SUMMARY),
                responseType,
                responseType == ResponseType.CHANNEL_RECOMMENDATION && recommendations.stream()
                        .anyMatch(recommendation -> recommendation.processable() && "CHATBOT".equals(recommendation.channel().name()))
                        ? businessActions(policy) : List.of(),
                responseType == ResponseType.CHANNEL_RECOMMENDATION ? guidance(policy) : null,
                tools.understanding()
        );
    }

    private static String guidance(PolicyEvaluation policy) {
        // Deliver official applicable text separately; the UI reveals it only after channel selection.
        return policy.matches().stream().flatMap(match -> match.branches().stream())
                .filter(branch -> "APPLICABLE".equals(branch.applicability()))
                .flatMap(branch -> java.util.stream.Stream.of(branch.officialSteps(), branch.restrictions(), branch.remainingChecks())
                        .filter(Objects::nonNull).flatMap(List::stream))
                .filter(text -> text != null && !text.isBlank()).distinct()
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    private static ResponseType responseType(PolicyEvaluation policy, ChannelEvaluation channels) {
        if (policy.status() == PolicyEvaluation.Status.NEEDS_INFORMATION) return ResponseType.FOLLOW_UP;
        if (channels == null) return ResponseType.GUIDANCE;
        return switch (channels.status()) {
            case "NEEDS_INFORMATION", "NEEDS_CONTEXT" -> ResponseType.FOLLOW_UP;
            case "RANKED" -> channels.recommendations().isEmpty()
                    ? ResponseType.UNRESOLVED : ResponseType.CHANNEL_RECOMMENDATION;
            case "UNSUPPORTED", "NO_ELIGIBLE_CHANNEL" -> ResponseType.UNRESOLVED;
            default -> ResponseType.GUIDANCE;
        };
    }

    /** Map only applicable policy branches to our two explicitly supported demo screens. */
    private static List<BusinessAction> businessActions(PolicyEvaluation policy) {
        return policy.matches().stream().flatMap(match -> match.branches().stream())
                .filter(branch -> "APPLICABLE".equals(branch.applicability()))
                .map(branch -> switch (branch.branchCode()) {
                    case "INTERBANK_AUTO_GUIDE", "INTRABANK_AUTO_GUIDE" -> new BusinessAction("AUTO_TRANSFER", "자동이체 메뉴 열기", true);
                    case "CARD_REPORT", "CARD_UNKNOWN_PAYMENT" -> new BusinessAction("CARD_LOSS", "카드 분실 안내 메뉴 열기", true);
                    default -> null;
                }).filter(Objects::nonNull).findFirst().map(List::of).orElseGet(List::of);
    }

    private static String fallbackAnswer(PolicyEvaluation policy, ChannelEvaluation channels) {
        if (policy.status() == PolicyEvaluation.Status.NEEDS_INFORMATION) {
            return "정확한 안내에 필요한 조건을 조금 더 알려 주세요.";
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
