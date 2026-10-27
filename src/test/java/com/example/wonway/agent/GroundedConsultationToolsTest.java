package com.example.wonway.agent;

import com.example.wonway.agent.exception.AgentUnavailableException;
import com.example.wonway.agent.model.GeneratedConsultation;
import com.example.wonway.common.contract.ChannelType;
import com.example.wonway.policy.model.ChannelEvaluation;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import java.util.List;
import java.util.Map;
import static com.example.wonway.agent.ConsultationToolsTest.*;
import static com.example.wonway.agent.ToolCallingAgentModelGatewayTest.slot;
import static org.assertj.core.api.Assertions.*;

class GroundedConsultationToolsTest {
    @Test void exposesNoModelSuppliedFactsScoresOrUrlsOnQueryTools() throws Exception {
        var actions = actions(new StubChannelRankingService(rankedEvaluation()));
        for (var callback : ToolCallbacks.from(actions)) {
            var schema = new com.fasterxml.jackson.databind.ObjectMapper().readTree(callback.getToolDefinition().inputSchema());
            if (callback.getToolDefinition().name().equals("findApplicablePolicies")) {
                assertThat(schema.path("properties").size()).isZero();
            } else if (callback.getToolDefinition().name().equals("rankConsultationChannels")) {
                assertThat(schema.path("properties").propertyStream().map(Map.Entry::getKey).toList()).containsExactly("requestGoal");
            } else if (callback.getToolDefinition().name().equals("updateConsultation")) {
                // Follow-up answers can supply only the changed slots, without inventing another intent.
                var extraction = schema.path("properties").path("extraction");
                assertThat(extraction.path("properties").has("intent")).isTrue();
                assertThat(extraction.path("required").toString()).doesNotContain("intent", "purpose", "newTopic");
            }
        }
    }

    @Test void rejectsOutOfOrderActionsAndUnconfirmedIndividualLookup() {
        var actions = actions(new StubChannelRankingService(rankedEvaluation()));
        assertThat(actions.findApplicablePolicies().status()).isEqualTo("REJECTED");
        assertThat(actions.rankConsultationChannels("GENERAL_GUIDANCE").status()).isEqualTo("REJECTED");
        update(actions, List.of());
        assertThat(actions.rankConsultationChannels("GENERAL_GUIDANCE").status()).isEqualTo("REJECTED");
        assertThat(actions.findApplicablePolicies().status()).isEqualTo("MATCHED");
        assertThat(actions.rankConsultationChannels("ACCOUNT_SPECIFIC_CHECK").status()).isEqualTo("REJECTED");
        assertThat(actions.rankConsultationChannels("UNKNOWN").status()).isEqualTo("REJECTED");
    }

    @Test void changingCustomerConditionsInvalidatesPreviouslyVerifiedResults() {
        var tools = ToolCallingAgentModelGatewayTest.tools();
        var actions = new GroundedConsultationTools(tools, new ConsultationStateMerger(), "카드 분실 전화 이메일", null);
        update(actions, List.of(slot("preferredChannel", "PHONE", "전화")));
        actions.findApplicablePolicies(); actions.rankConsultationChannels("GENERAL_GUIDANCE");
        assertThat(tools.verifiedRecommendations()).isNotEmpty();
        update(actions, List.of(slot("preferredChannel", "EMAIL", "이메일")));
        assertThat(tools.lastPolicyEvaluation()).isNull();
        assertThat(tools.lastChannelEvaluation()).isNull();
        assertThat(tools.verifiedRecommendations()).isEmpty();
        assertThat(actions.rankConsultationChannels("GENERAL_GUIDANCE").status()).isEqualTo("REJECTED");
        assertThatThrownBy(() -> actions.validateFinal(new GeneratedConsultation("추천합니다", "안내", false)))
                .isInstanceOf(AgentUnavailableException.class).hasMessageContaining("정책 조회");
    }

    @Test void cannotBypassSelfServiceContextByChangingTheRankingGoal() {
        var actions = new GroundedConsultationTools(ToolCallingAgentModelGatewayTest.tools(), new ConsultationStateMerger(),
                "타행 자동이체 날짜 변경", null);
        actions.updateConsultation(new ConsultationUnderstanding.Extraction("INTERBANK_AUTO_TRANSFER", "타행 자동이체", false,
                List.of(slot("transferType", "INTERBANK", "타행"), slot("operation", "CHANGE_DATE", "날짜 변경")), List.of(), "날짜 변경"));
        actions.findApplicablePolicies();
        assertThat(actions.rankConsultationChannels("GENERAL_GUIDANCE").status()).isEqualTo("REJECTED");
        assertThat(actions.rankConsultationChannels("SELF_SERVICE_GUIDANCE").missingFields()).containsExactly("selfServiceAvailable");
    }

    @Test void requiresMissingCustomerContextButDoesNotAskCustomersToVerifyBankAvailability() {
        var unknownCustomer = new ChannelEvaluation("RANKED", rankedEvaluation().recommendations(),
                List.of(new ChannelEvaluation.Decision(ChannelType.EMAIL, "UNKNOWN", null, null,
                        List.of("비긴급 여부·비동기 회신 수용·실시간 문답 필요 여부 미확인"), List.of())), "test");
        var actions = actions(new StubChannelRankingService(unknownCustomer));
        update(actions, List.of(slot("preferredChannel", "EMAIL", "이메일")));
        actions.findApplicablePolicies();
        assertThat(actions.rankConsultationChannels("GENERAL_GUIDANCE").status()).isEqualTo("NEEDS_INFORMATION");
        assertThatThrownBy(() -> actions.validateFinal(new GeneratedConsultation("추천합니다", "안내", false)))
                .isInstanceOf(AgentUnavailableException.class).hasMessageContaining("고객 조건");
        assertThatCode(() -> actions.validateFinal(new GeneratedConsultation("급하게 필요한가요?", "안내", true)))
                .doesNotThrowAnyException();
        var operatingUnknown = new ChannelEvaluation("NO_ELIGIBLE_CHANNEL", List.of(),
                List.of(new ChannelEvaluation.Decision(ChannelType.EMAIL, "UNKNOWN", null, null,
                        List.of("지원 범위 또는 현재 운영 상태가 확인되지 않음"), List.of())), "test");
        var unavailable = actions(new StubChannelRankingService(operatingUnknown));
        update(unavailable, List.of()); unavailable.findApplicablePolicies();
        assertThat(unavailable.rankConsultationChannels("GENERAL_GUIDANCE").status()).isEqualTo("NO_ELIGIBLE_CHANNEL");
        assertThatCode(() -> unavailable.validateFinal(new GeneratedConsultation("운영정보 확인이 필요합니다", "안내", false)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> unavailable.validateFinal(new GeneratedConsultation("은행이 운영 중인가요?", "안내", true)))
                .isInstanceOf(AgentUnavailableException.class).hasMessageContaining("고객 조건");
    }

    @Test void cannotFinishRecommendationWhenPolicyStillNeedsCustomerFacts() {
        var missing = new com.example.wonway.policy.model.PolicyEvaluation(
                com.example.wonway.policy.model.PolicyEvaluation.Status.NEEDS_INFORMATION, MATCHED.matches(), List.of("requestKind"), null);
        var actions = new GroundedConsultationTools(new ConsultationTools(new StubPolicyService(missing),
                new StubChannelRankingService(rankedEvaluation())), new ConsultationStateMerger(), "카드 분실 전화 이메일", null);
        update(actions, List.of()); actions.findApplicablePolicies();
        assertThatThrownBy(() -> actions.validateFinal(new GeneratedConsultation("추천합니다", "안내", false)))
                .isInstanceOf(AgentUnavailableException.class).hasMessageContaining("고객 조건");
    }

    private static GroundedConsultationTools actions(StubChannelRankingService channels) {
        return new GroundedConsultationTools(new ConsultationTools(new StubPolicyService(MATCHED), channels),
                new ConsultationStateMerger(), "카드 분실 전화 이메일", null);
    }
    private static void update(GroundedConsultationTools actions, List<ConsultationUnderstanding.Slot> context) {
        actions.updateConsultation(new ConsultationUnderstanding.Extraction("CARD_LOSS", "카드 분실", false, List.of(), context, "카드 분실 안내"));
    }
}
