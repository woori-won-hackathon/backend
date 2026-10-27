package com.example.wonway.agent;

import com.example.wonway.common.contract.ChannelRankingCondition;
import com.example.wonway.common.contract.ChannelRecommendation;
import com.example.wonway.common.contract.ChannelType;
import com.example.wonway.common.contract.PolicyResult;
import com.example.wonway.common.contract.PolicySearchCondition;
import com.example.wonway.policy.model.ChannelEvaluation;
import com.example.wonway.policy.model.PolicyEvaluation;
import com.example.wonway.policy.service.ChannelRankingService;
import com.example.wonway.policy.service.PolicyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ConsultationToolsTest {

    static final PolicyResult POLICY = new PolicyResult(
            null, "CARD_LOSS", "카드 분실", "{}", null, null
    );
    static final PolicyEvaluation MATCHED = new PolicyEvaluation(
            PolicyEvaluation.Status.MATCHED,
            List.of(new PolicyEvaluation.Match(
                    POLICY,
                    List.of(new PolicyEvaluation.Branch(
                            "CARD_REPORT", "APPLICABLE", List.of(), List.of("신고"), List.of(),
                            List.of(), List.of("https://example.com/card"), "WEB_CHECKED", "WOORI_BANK"
                    )),
                    "APPROVED_POLICY"
            )),
            List.of(),
            null
    );

    @Test
    void exposesTwoSpringAiToolsWithOptionalFields() throws Exception {
        ConsultationTools tools = new ConsultationTools(
                new StubPolicyService(MATCHED),
                new StubChannelRankingService(rankedEvaluation())
        );

        ToolCallback[] callbacks = ToolCallbacks.from(tools);

        assertThat(Arrays.stream(callbacks).map(callback -> callback.getToolDefinition().name()))
                .containsExactlyInAnyOrder("findApplicablePolicies", "rankConsultationChannels");

        ToolCallback policyTool = Arrays.stream(callbacks)
                .filter(callback -> callback.getToolDefinition().name().equals("findApplicablePolicies"))
                .findFirst()
                .orElseThrow();
        JsonNode schema = new ObjectMapper().readTree(policyTool.getToolDefinition().inputSchema());
        assertThat(schema.path("required").toString())
                .contains("intent")
                .doesNotContain("customerType", "productType", "requiresIdentityVerification", "facts");
        assertThat(schema.path("properties").has("facts")).isTrue();
    }

    @Test
    void channelToolRequeriesPolicyAndPassesOnlyServerPolicies() {
        AtomicReference<PolicySearchCondition> policyInput = new AtomicReference<>();
        AtomicReference<ChannelRankingCondition> channelInput = new AtomicReference<>();
        PolicyService policies = new StubPolicyService(MATCHED) {
            @Override
            public PolicyEvaluation evaluate(PolicySearchCondition condition) {
                policyInput.set(condition);
                return MATCHED;
            }
        };
        ChannelRankingService channels = new StubChannelRankingService(rankedEvaluation()) {
            @Override
            public ChannelEvaluation evaluate(ChannelRankingCondition condition) {
                channelInput.set(condition);
                return rankedEvaluation();
            }
        };
        ConsultationTools tools = new ConsultationTools(policies, channels);

        ChannelEvaluation result = tools.rankConsultationChannels(
                "CARD_LOSS", "INDIVIDUAL", "CARD", Map.of("requestKind", "REPORT_LOSS"),
                "GENERAL_GUIDANCE", null, null, null, true, null, true, "PHONE", true
        );

        assertThat(policyInput.get().facts()).containsEntry("requestKind", "REPORT_LOSS");
        assertThat(channelInput.get().policies()).containsExactly(POLICY);
        assertThat(channelInput.get().context().urgent()).isTrue();
        assertThat(channelInput.get().context().preferredChannel()).isEqualTo("PHONE");
        assertThat(result.status()).isEqualTo("RANKED");
        assertThat(tools.verifiedRecommendations()).hasSize(1);
    }

    @Test
    void unsupportedPolicySkipsChannelServiceAndClearsRecommendations() {
        PolicyEvaluation unsupported = new PolicyEvaluation(
                PolicyEvaluation.Status.UNSUPPORTED, List.of(), List.of(), "지금은 지원하지 않는 기능입니다"
        );
        AtomicInteger channelCalls = new AtomicInteger();
        ChannelRankingService channels = new StubChannelRankingService(rankedEvaluation()) {
            @Override
            public ChannelEvaluation evaluate(ChannelRankingCondition condition) {
                channelCalls.incrementAndGet();
                return rankedEvaluation();
            }
        };
        ConsultationTools tools = new ConsultationTools(new StubPolicyService(unsupported), channels);

        ChannelEvaluation result = tools.rankConsultationChannels(
                "UNKNOWN", null, null, Map.of(), "GENERAL_GUIDANCE",
                null, null, null, null, null, null, null, null
        );

        assertThat(result.status()).isEqualTo("UNSUPPORTED");
        assertThat(channelCalls).hasValue(0);
        assertThat(tools.verifiedRecommendations()).isEmpty();
    }

    static ChannelEvaluation rankedEvaluation() {
        ChannelRecommendation recommendation = new ChannelRecommendation(
                ChannelType.PHONE,
                77,
                true,
                List.of("검증된 전화 상담"),
                "https://example.com/contact"
        );
        return new ChannelEvaluation("RANKED", List.of(recommendation), List.of(), "test-v1");
    }

    static class StubPolicyService implements PolicyService {
        private final PolicyEvaluation evaluation;

        StubPolicyService(PolicyEvaluation evaluation) {
            this.evaluation = evaluation;
        }

        @Override
        public List<PolicyResult> findApplicablePolicies(PolicySearchCondition condition) {
            return evaluate(condition).policies();
        }

        @Override
        public PolicyEvaluation evaluate(PolicySearchCondition condition) {
            return evaluation;
        }
    }

    static class StubChannelRankingService implements ChannelRankingService {
        private final ChannelEvaluation evaluation;

        StubChannelRankingService(ChannelEvaluation evaluation) {
            this.evaluation = evaluation;
        }

        @Override
        public List<ChannelRecommendation> rankChannels(ChannelRankingCondition condition) {
            return evaluate(condition).recommendations();
        }

        @Override
        public ChannelEvaluation evaluate(ChannelRankingCondition condition) {
            return evaluation;
        }
    }
}
