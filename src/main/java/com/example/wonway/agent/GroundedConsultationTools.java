package com.example.wonway.agent;

import com.example.wonway.agent.exception.AgentUnavailableException;
import com.example.wonway.agent.model.GeneratedConsultation;
import com.example.wonway.policy.model.ChannelEvaluation;
import com.example.wonway.policy.model.PolicyEvaluation;
import org.springframework.ai.tool.annotation.Tool;
import java.util.List;
import java.util.Set;

/** Request-scoped model tools. The model chooses actions; these methods enforce their preconditions. */
public final class GroundedConsultationTools {
    private final ConsultationTools delegate;
    private final ConsultationStateMerger merger;
    private final String message;
    private ConsultationUnderstanding state;
    private boolean updated;

    public record Outcome(String status, List<String> missingFields, Object result) {}

    GroundedConsultationTools(ConsultationTools delegate, ConsultationStateMerger merger,
                              String message, ConsultationUnderstanding before) {
        this.delegate = delegate;
        this.merger = merger;
        this.message = message;
        this.state = before;
        delegate.understanding(before);
    }

    @Tool(description = "현재 메시지에서 확인된 의도·조건 변경분을 원문 근거와 함께 저장한다. "
            + "정책 조회 전에 호출한다. 이전 조건은 서버가 보존한다. 누락 boolean은 false로 만들지 않는다. "
            + "evidence와 intentEvidence는 현재 사용자 메시지의 연속 원문이어야 한다.")
    public Outcome updateConsultation(ConsultationUnderstanding.Extraction extraction) {
        if (extraction == null) return rejected("문의 해석 정보가 필요합니다.");
        state = merger.merge(state, extraction, message);
        delegate.understanding(state);
        updated = true;
        return new Outcome("UPDATED", List.of(), state);
    }

    @Tool(description = "서버가 검증·보관한 현재 상담 조건으로 정책 DB를 조회한다. "
            + "updateConsultation 후 호출한다. NEEDS_INFORMATION의 missingFields를 보고 필요한 추가 질문을 선택한다. "
            + "REVIEW_REQUIRED는 은행 정책 근거의 검토 상태이며 고객 답변으로 해소하지 않는다.")
    public Outcome findApplicablePolicies() {
        if (!updated) return rejected("먼저 updateConsultation으로 현재 조건을 확인하세요.");
        if (state.intent() == null) return new Outcome("NEEDS_INFORMATION", List.of("intent"), null);
        if ("AUTO_TRANSFER_MANAGEMENT".equals(state.intent())) {
            return new Outcome("NEEDS_INFORMATION", List.of("transferType"), null);
        }
        PolicyEvaluation policy = delegate.findApplicablePolicies(state.intent(), null, null, null, state.facts());
        return new Outcome(policy.status().name(), policy.missingFields(), policy);
    }

    @Tool(description = "현재 요청에서 MATCHED 정책을 조회한 뒤 상담 채널을 평가한다. "
            + "requestGoal은 GENERAL_GUIDANCE, SELF_SERVICE_GUIDANCE, ACCOUNT_SPECIFIC_CHECK, EXECUTION_REQUEST 중 하나다. "
            + "고객 조건·정책·점수·URL은 서버가 제공하므로 입력하지 않는다. "
            + "NEEDS_INFORMATION이면 누락 고객 조건만 질문한다. 운영 상태 UNKNOWN은 고객에게 확인받지 않는다.")
    public Outcome rankConsultationChannels(String requestGoal) {
        PolicyEvaluation policy = delegate.lastPolicyEvaluation();
        if (!updated || policy == null || policy.status() != PolicyEvaluation.Status.MATCHED) {
            return rejected("현재 조건의 정책 조회 결과가 MATCHED일 때만 채널 평가를 실행할 수 있습니다.");
        }
        if (requestGoal == null || !Set.of("GENERAL_GUIDANCE", "SELF_SERVICE_GUIDANCE",
                "ACCOUNT_SPECIFIC_CHECK", "EXECUTION_REQUEST").contains(requestGoal)) {
            return rejected("지원하는 requestGoal을 선택하세요.");
        }
        boolean individual = Boolean.TRUE.equals(bool("needsAccountSpecificCheck"));
        if (individual != "ACCOUNT_SPECIFIC_CHECK".equals(requestGoal) && !"EXECUTION_REQUEST".equals(requestGoal)) {
            return rejected("개별 계좌 확인 목적은 고객이 명시한 조회 요청과 일치해야 합니다.");
        }
        boolean execution = "EXECUTION_REQUEST".equals(requestGoal);
        if (!execution && !individual && state.intent().contains("AUTO_TRANSFER")
                && !"SELF_SERVICE_GUIDANCE".equals(requestGoal)) {
            return rejected("자동이체 방법 안내 목적은 SELF_SERVICE_GUIDANCE로 평가하세요.");
        }
        if (!execution && individual && !state.context().containsKey("identityVerificationAvailable")) {
            return new Outcome("NEEDS_INFORMATION", List.of("identityVerificationAvailable"), null);
        }
        boolean humanPreferred = Set.of("PHONE", "EMAIL").contains(String.valueOf(state.context().get("preferredChannel")));
        if (!execution && state.intent().contains("AUTO_TRANSFER") && !individual && !humanPreferred
                && !Boolean.TRUE.equals(bool("selfServiceAttemptFailed")) && !state.context().containsKey("selfServiceAvailable")) {
            return new Outcome("NEEDS_INFORMATION", List.of("selfServiceAvailable"), null);
        }
        ChannelEvaluation channels = delegate.rankConsultationChannels(state.intent(), null, null, state.facts(),
                requestGoal, bool("needsAccountSpecificCheck"), bool("selfServiceAvailable"),
                bool("selfServiceAttemptFailed"), bool("urgent"), bool("acceptsAsyncReply"),
                bool("realTimeConversationNeeded"), (String) state.context().get("preferredChannel"),
                bool("identityVerificationAvailable"));
        return new Outcome(customerContextMissing() ? "NEEDS_INFORMATION" : channels.status(), List.of(), channels);
    }

    void validateFinal(GeneratedConsultation generated) {
        if (generated == null || generated.answer() == null || generated.answer().isBlank()
                || generated.intentClarificationNeeded() == null) {
            throw new AgentUnavailableException("모델이 유효한 상담 응답을 반환하지 않았습니다.");
        }
        boolean question = Boolean.TRUE.equals(generated.intentClarificationNeeded());
        if (!question && !updated) throw new AgentUnavailableException("모델이 상담 조건을 확인하지 않았습니다.");
        PolicyEvaluation policy = delegate.lastPolicyEvaluation();
        if (!question && policy == null) throw new AgentUnavailableException("현재 요청의 정책 조회 도구 결과가 필요합니다.");
        if (!question && policy.status() == PolicyEvaluation.Status.MATCHED && delegate.lastChannelEvaluation() == null) {
            throw new AgentUnavailableException("현재 요청의 채널 평가 도구 결과가 필요합니다.");
        }
        if (question && policy != null && (policy.status() == PolicyEvaluation.Status.UNSUPPORTED
                || policy.status() == PolicyEvaluation.Status.REVIEW_REQUIRED)) {
            throw new AgentUnavailableException("고객 추가 질문으로 해결할 수 없는 정책 상태입니다.");
        }
        if (question && delegate.lastChannelEvaluation() != null && !customerContextMissing()) {
            throw new AgentUnavailableException("추가 고객 조건이 필요하지 않은 채널 평가 상태입니다.");
        }
        if (!question && (customerContextMissing()
                || policy != null && policy.status() == PolicyEvaluation.Status.NEEDS_INFORMATION)) {
            throw new AgentUnavailableException("필요한 고객 조건이 확인되기 전에 추천을 종료할 수 없습니다.");
        }
    }

    private boolean customerContextMissing() {
        ChannelEvaluation channels = delegate.lastChannelEvaluation();
        if (channels == null) return false;
        if (Set.of("NEEDS_INFORMATION", "NEEDS_CONTEXT").contains(channels.status())) return true;
        return channels.decisions().stream().filter(decision -> "UNKNOWN".equals(decision.eligibility()))
                .filter(decision -> decision.reasons().stream().noneMatch(reason -> reason.contains("운영 상태") || reason.contains("지원 범위")))
                .anyMatch(decision -> channels.recommendations().isEmpty()
                        || decision.channel().name().equals(state.context().get("preferredChannel")));
    }

    private Boolean bool(String key) { return (Boolean) state.context().get(key); }
    private static Outcome rejected(String reason) { return new Outcome("REJECTED", List.of(), reason); }
}
