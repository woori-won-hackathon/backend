package com.example.wonway.agent;

import com.example.wonway.agent.exception.AgentUnavailableException;
import com.example.wonway.agent.model.AgentResult;
import com.example.wonway.agent.model.GeneratedConsultation;
import com.example.wonway.common.contract.ChannelRecommendation;
import com.example.wonway.policy.model.PolicyEvaluation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.wonway.agent.ConsultationToolsTest.MATCHED;
import static com.example.wonway.agent.ConsultationToolsTest.StubChannelRankingService;
import static com.example.wonway.agent.ConsultationToolsTest.StubPolicyService;
import static com.example.wonway.agent.ConsultationToolsTest.rankedEvaluation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringAiAgentServiceTest {

    @Test
    void returnsOnlyRecommendationsVerifiedByCurrentRequestTools() {
        AgentModelGateway gateway = (message, tools) -> {
            tools.rankConsultationChannels(
                    "CARD_LOSS", "INDIVIDUAL", "CARD", Map.of("requestKind", "REPORT_LOSS"),
                    "GENERAL_GUIDANCE", null, null, null, true, null, true, "PHONE", true
            );
            return new GeneratedConsultation("전화 상담을 이용할 수 있습니다.", "카드 분실 상담 채널 안내");
        };
        SpringAiAgentService service = new SpringAiAgentService(
                gateway,
                new StubPolicyService(MATCHED),
                new StubChannelRankingService(rankedEvaluation())
        );

        AgentResult result = service.chat(UUID.randomUUID(), "우리카드를 잃어버려 신고하려고 해요");

        assertThat(result.answer()).isEqualTo("전화 상담을 이용할 수 있습니다.");
        assertThat(result.recommendations())
                .extracting(ChannelRecommendation::score)
                .containsExactly(77);
        assertThat(result.consultationSummary()).isEqualTo("카드 분실 상담 채널 안내");
    }

    @Test
    void createsIndependentToolsForEveryMessageEvenWithSameSessionId() {
        List<ConsultationTools> requestTools = new ArrayList<>();
        AgentModelGateway gateway = (message, tools) -> {
            requestTools.add(tools);
            tools.findApplicablePolicies("CARD_LOSS", null, "CARD", null, Map.of());
            return new GeneratedConsultation("추가 정보를 알려 주세요.", "카드 상담 조건 확인");
        };
        PolicyEvaluation needsInformation = new PolicyEvaluation(
                PolicyEvaluation.Status.NEEDS_INFORMATION,
                MATCHED.matches(),
                List.of("requestKind"),
                null
        );
        SpringAiAgentService service = new SpringAiAgentService(
                gateway,
                new StubPolicyService(needsInformation),
                new StubChannelRankingService(rankedEvaluation())
        );
        UUID sessionId = UUID.randomUUID();

        service.chat(sessionId, "카드 문의입니다");
        service.chat(sessionId, "분실 신고입니다");

        assertThat(requestTools).hasSize(2);
        assertThat(requestTools.get(0)).isNotSameAs(requestTools.get(1));
    }

    @Test
    void unsupportedResultOverridesModelTextAndNeverReturnsRecommendations() {
        PolicyEvaluation unsupported = new PolicyEvaluation(
                PolicyEvaluation.Status.UNSUPPORTED, List.of(), List.of(), "지금은 지원하지 않는 기능입니다"
        );
        AgentModelGateway gateway = (message, tools) -> {
            tools.findApplicablePolicies("UNKNOWN", null, null, null, Map.of());
            return new GeneratedConsultation("모델이 임의로 만든 답변", "임의 요약");
        };
        SpringAiAgentService service = new SpringAiAgentService(
                gateway,
                new StubPolicyService(unsupported),
                new StubChannelRankingService(rankedEvaluation())
        );

        AgentResult result = service.chat(UUID.randomUUID(), "지원하지 않는 문의");

        assertThat(result.answer()).isEqualTo(SpringAiAgentService.UNSUPPORTED_MESSAGE);
        assertThat(result.recommendations()).isEmpty();
    }

    @Test
    void rejectsModelResponseWhenRequiredPolicyToolWasNotCalled() {
        AgentModelGateway gateway = (message, tools) -> new GeneratedConsultation("임의 답변", "임의 요약");
        SpringAiAgentService service = new SpringAiAgentService(
                gateway,
                new StubPolicyService(MATCHED),
                new StubChannelRankingService(rankedEvaluation())
        );

        assertThatThrownBy(() -> service.chat(UUID.randomUUID(), "카드 문의"))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("정책 조회 도구");
    }

    @Test
    void allowsClarifyingQuestionWithoutInventingAnIntentOrCallingTools() {
        AgentModelGateway gateway = (message, tools) -> new GeneratedConsultation(
                "어떤 업무가 궁금하신지 조금 더 자세히 알려 주세요.",
                "상담 업무 확인 필요",
                true
        );
        SpringAiAgentService service = new SpringAiAgentService(
                gateway,
                new StubPolicyService(MATCHED),
                new StubChannelRankingService(rankedEvaluation())
        );

        AgentResult result = service.chat(UUID.randomUUID(), "이거 어떻게 해요?");

        assertThat(result.answer()).contains("어떤 업무");
        assertThat(result.recommendations()).isEmpty();
    }
}
