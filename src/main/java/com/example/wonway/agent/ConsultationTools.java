package com.example.wonway.agent;

import com.example.wonway.common.contract.ChannelRankingCondition;
import com.example.wonway.common.contract.ChannelRecommendation;
import com.example.wonway.common.contract.ConsultationContext;
import com.example.wonway.common.contract.PolicySearchCondition;
import com.example.wonway.policy.model.ChannelEvaluation;
import com.example.wonway.policy.model.PolicyEvaluation;
import com.example.wonway.policy.service.ChannelRankingService;
import com.example.wonway.policy.service.PolicyService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 현재 요청에서만 사용하는 Spring AI 도구 모음입니다.
 * 마지막 검증 결과를 보관하므로 singleton Bean으로 등록하지 않습니다.
 */
public final class ConsultationTools {
    private static final Logger log = LoggerFactory.getLogger(ConsultationTools.class);

    private final PolicyService policyService;
    private final ChannelRankingService channelRankingService;
    private PolicyEvaluation lastPolicyEvaluation;
    private ChannelEvaluation lastChannelEvaluation;
    private final UUID sessionId;
    private ConsultationUnderstanding understanding;
    void understanding(ConsultationUnderstanding value) {
        // Results from older conditions must not survive a model's state correction.
        clearResults();
        understanding = value;
    }
    ConsultationUnderstanding understanding() { return understanding; }

    public ConsultationTools(PolicyService policyService, ChannelRankingService channelRankingService) {
        this(policyService, channelRankingService, null);
    }

    public ConsultationTools(PolicyService policyService, ChannelRankingService channelRankingService, UUID sessionId) {
        this.policyService = policyService;
        this.channelRankingService = channelRankingService;
        this.sessionId = sessionId;
    }

    @Tool(
            name = "findApplicablePolicies",
            description = "동일 세션의 현재 및 이전 사용자 진술로 확인한 사실만으로 정책 적용 조건을 조회한다. "
                    + "최신 정정을 우선하고 다른 업무의 조건이나 AI가 추측한 사실은 사용하지 않는다."
    )
    public PolicyEvaluation findApplicablePolicies(
            String intent,
            @ToolParam(required = false) String customerType,
            @ToolParam(required = false) String productType,
            @ToolParam(required = false) Boolean requiresIdentityVerification,
            @ToolParam(required = false) Map<String, Object> facts
    ) {
        clearResults();
        lastPolicyEvaluation = policyService.evaluate(new PolicySearchCondition(
                intent,
                customerType,
                productType,
                requiresIdentityVerification,
                facts
        ));
        logPolicyResult();
        return lastPolicyEvaluation;
    }

    @Tool(
            name = "rankConsultationChannels",
            description = "동일 세션의 사용자 진술에서 확인한 고객 상황과 새로 조회한 정책으로 상담 채널을 평가한다. "
                    + "점수, 정책 본문, 운영 상태는 입력하지 않고 미언급 Boolean은 null로 둔다."
    )
    public ChannelEvaluation rankConsultationChannels(
            String intent,
            @ToolParam(required = false) String customerType,
            @ToolParam(required = false) String productType,
            @ToolParam(required = false) Map<String, Object> facts,
            String requestGoal,
            @ToolParam(required = false) Boolean needsAccountSpecificCheck,
            @ToolParam(required = false) Boolean selfServiceAvailable,
            @ToolParam(required = false) Boolean selfServiceAttemptFailed,
            @ToolParam(required = false) Boolean urgent,
            @ToolParam(required = false) Boolean acceptsAsyncReply,
            @ToolParam(required = false) Boolean realTimeConversationNeeded,
            @ToolParam(required = false) String preferredChannel,
            @ToolParam(required = false) Boolean identityVerificationAvailable
    ) {
        clearResults();
        Map<String, Object> currentFacts = facts == null ? Map.of() : facts;
        lastPolicyEvaluation = policyService.evaluate(new PolicySearchCondition(
                intent,
                customerType,
                productType,
                null,
                currentFacts
        ));

        logPolicyResult();
        if (lastPolicyEvaluation.status() != PolicyEvaluation.Status.MATCHED) {
            lastChannelEvaluation = new ChannelEvaluation(
                    lastPolicyEvaluation.status().name(),
                    List.of(),
                    List.of(),
                    "NOT_EVALUATED"
            );
            logChannelResult();
            return lastChannelEvaluation;
        }

        ConsultationContext context = new ConsultationContext(
                requestGoal,
                needsAccountSpecificCheck,
                selfServiceAvailable,
                selfServiceAttemptFailed,
                urgent,
                acceptsAsyncReply,
                realTimeConversationNeeded,
                preferredChannel
        );
        lastChannelEvaluation = channelRankingService.evaluate(new ChannelRankingCondition(
                intent,
                customerType,
                productType,
                identityVerificationAvailable,
                lastPolicyEvaluation.policies(),
                currentFacts,
                context
        ));
        logChannelResult();
        return lastChannelEvaluation;
    }

    // Only server status and counts are logged; never user text, extracted facts, or model answers.
    private void logPolicyResult() {
        log.info("정책 조회 결과 sessionId={} status={} matches={} missingFields={}", sessionId,
                lastPolicyEvaluation.status(), lastPolicyEvaluation.matches().size(), lastPolicyEvaluation.missingFields().size());
    }

    private void logChannelResult() {
        log.info("채널 평가 결과 sessionId={} status={} recommendations={}", sessionId,
                lastChannelEvaluation.status(), lastChannelEvaluation.recommendations().size());
    }

    /** 모델 출력과 분리해 서버가 검증한 현재 요청의 추천만 반환합니다. */
    public List<ChannelRecommendation> verifiedRecommendations() {
        if (lastChannelEvaluation == null || !"RANKED".equals(lastChannelEvaluation.status())) {
            return List.of();
        }
        return List.copyOf(lastChannelEvaluation.recommendations());
    }

    public PolicyEvaluation lastPolicyEvaluation() {
        return lastPolicyEvaluation;
    }

    public ChannelEvaluation lastChannelEvaluation() {
        return lastChannelEvaluation;
    }

    private void clearResults() {
        lastPolicyEvaluation = null;
        lastChannelEvaluation = null;
    }
}
