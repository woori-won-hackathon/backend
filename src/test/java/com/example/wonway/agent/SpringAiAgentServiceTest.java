package com.example.wonway.agent;

import com.example.wonway.agent.exception.AgentUnavailableException;
import com.example.wonway.agent.model.AgentResult;
import com.example.wonway.agent.model.GeneratedConsultation;
import com.example.wonway.agent.model.ResponseType;
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
    private final ConversationMemory memory = new ConversationMemory();

    private UUID newSession() {
        UUID id = UUID.randomUUID();
        memory.open(id);
        return id;
    }

    @Test
    void forwardsSameSessionHistoryToModelAndReevaluatesToolsForFollowUp() {
        List<List<ConversationMemory.Turn>> seen = new ArrayList<>();
        AgentModelGateway gateway = (message, history, tools) -> {
            seen.add(history);
            return new GeneratedConsultation("확인할 업무를 알려 주세요.", "업무 확인", true);
        };
        SpringAiAgentService service = new SpringAiAgentService(gateway,
                new StubPolicyService(MATCHED), new StubChannelRankingService(rankedEvaluation()), memory);
        UUID first = newSession(), other = newSession();
        service.chat(first, "처음 문의");
        service.chat(first, "후속 답변");
        service.chat(other, "새 문의");
        assertThat(seen.get(0)).isEmpty();
        assertThat(seen.get(1)).containsExactly(new ConversationMemory.Turn("처음 문의", "확인할 업무를 알려 주세요."));
        assertThat(seen.get(2)).isEmpty();
    }

    @Test
    void returnsOnlyRecommendationsVerifiedByCurrentRequestTools() {
        AgentModelGateway gateway = (message, history, tools) -> {
            tools.rankConsultationChannels(
                    "CARD_LOSS", "INDIVIDUAL", "CARD", Map.of("requestKind", "REPORT_LOSS"),
                    "GENERAL_GUIDANCE", null, null, null, true, null, true, "PHONE", true
            );
            return new GeneratedConsultation("전화 상담을 이용할 수 있습니다.", "카드 분실 상담 채널 안내");
        };
        SpringAiAgentService service = new SpringAiAgentService(
                gateway,
                new StubPolicyService(MATCHED),
                new StubChannelRankingService(rankedEvaluation()), memory
        );

        AgentResult result = service.chat(newSession(), "우리카드를 잃어버려 신고하려고 해요");

        assertThat(result.answer()).isEqualTo("전화 상담을 이용할 수 있습니다.");
        assertThat(result.responseType()).isEqualTo(ResponseType.CHANNEL_RECOMMENDATION);
        assertThat(result.actions()).as("PHONE recommendations do not expose chatbot mock menus").isEmpty();

        assertThat(result.recommendations())
                .extracting(ChannelRecommendation::score)
                .containsExactly(77);
        assertThat(result.consultationSummary()).isEqualTo("카드 분실 상담 채널 안내");
    }

    @Test
    void createsIndependentToolsForEveryMessageEvenWithSameSessionId() {
        List<ConsultationTools> requestTools = new ArrayList<>();
        AgentModelGateway gateway = (message, history, tools) -> {
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
                new StubChannelRankingService(rankedEvaluation()), memory
        );
        UUID sessionId = newSession();

        var first = service.chat(sessionId, "카드 문의입니다");
        var second = service.chat(sessionId, "분실 신고입니다");
        assertThat(first.responseType()).isEqualTo(ResponseType.FOLLOW_UP);
        assertThat(second.responseType()).isEqualTo(ResponseType.FOLLOW_UP);
        assertThat(second.actions()).isEmpty();
        assertThat(second.recommendations()).isEmpty();

        assertThat(requestTools).hasSize(2);
        assertThat(requestTools.get(0)).isNotSameAs(requestTools.get(1));
    }

    @Test
    void unsupportedResultOverridesModelTextAndNeverReturnsRecommendations() {
        PolicyEvaluation unsupported = new PolicyEvaluation(
                PolicyEvaluation.Status.UNSUPPORTED, List.of(), List.of(), "지금은 지원하지 않는 기능입니다"
        );
        AgentModelGateway gateway = (message, history, tools) -> {
            tools.findApplicablePolicies("UNKNOWN", null, null, null, Map.of());
            return new GeneratedConsultation("모델이 임의로 만든 답변", "임의 요약");
        };
        SpringAiAgentService service = new SpringAiAgentService(
                gateway,
                new StubPolicyService(unsupported),
                new StubChannelRankingService(rankedEvaluation()), memory
        );

        AgentResult result = service.chat(newSession(), "지원하지 않는 문의");

        assertThat(result.answer()).isEqualTo(SpringAiAgentService.UNSUPPORTED_MESSAGE);
        assertThat(result.recommendations()).isEmpty();
        assertThat(result.responseType()).isEqualTo(ResponseType.UNRESOLVED);
        assertThat(result.actions()).isEmpty();
    }

    @Test
    void rejectsModelResponseWhenRequiredPolicyToolWasNotCalled() {
        AgentModelGateway gateway = (message, history, tools) -> new GeneratedConsultation("임의 답변", "임의 요약");
        SpringAiAgentService service = new SpringAiAgentService(
                gateway,
                new StubPolicyService(MATCHED),
                new StubChannelRankingService(rankedEvaluation()), memory
        );

        assertThatThrownBy(() -> service.chat(newSession(), "카드 문의"))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("정책 조회 도구");
    }

    @Test
    void allowsClarifyingQuestionWithoutInventingAnIntentOrCallingTools() {
        AgentModelGateway gateway = (message, history, tools) -> new GeneratedConsultation(
                "어떤 업무가 궁금하신지 조금 더 자세히 알려 주세요.",
                "상담 업무 확인 필요",
                true
        );
        SpringAiAgentService service = new SpringAiAgentService(
                gateway,
                new StubPolicyService(MATCHED),
                new StubChannelRankingService(rankedEvaluation()), memory
        );

        AgentResult result = service.chat(newSession(), "이거 어떻게 해요?");

        assertThat(result.answer()).contains("어떤 업무");
        assertThat(result.recommendations()).isEmpty();
        assertThat(result.responseType()).isEqualTo(ResponseType.FOLLOW_UP);
    }

    @Test
    void channelConditionsStillMissingReturnFollowUpWithoutMenuOrStaleRecommendation() {
        AgentModelGateway gateway = (message, history, tools) -> {
            tools.rankConsultationChannels("CARD_LOSS", null, null, Map.of(),
                    "GENERAL_GUIDANCE", null, null, null, null, null, null, null, null);
            return new GeneratedConsultation("이용 가능한 인증 수단이 있나요?", "인증 조건 확인 중");
        };
        var channels = new com.example.wonway.policy.model.ChannelEvaluation(
                "NEEDS_CONTEXT", List.of(), List.of(), "test");
        var service = new SpringAiAgentService(gateway, new StubPolicyService(MATCHED),
                new StubChannelRankingService(channels), memory);
        var result = service.chat(newSession(), "이어서 문의");
        assertThat(result.responseType()).isEqualTo(ResponseType.FOLLOW_UP);
        assertThat(result.actions()).isEmpty();
        assertThat(result.recommendations()).isEmpty();
    }

    @Test
    void doesNotInventAMenuForOtherPolicyBranchesEvenWhenAnswerMentionsCardLoss() {
        var release = new PolicyEvaluation.Branch("CARD_RELEASE", "APPLICABLE", List.of(),
                List.of(), List.of(), List.of(), List.of(), "WEB_CHECKED", "WOORI_BANK");
        var policy = new PolicyEvaluation(PolicyEvaluation.Status.MATCHED,
                List.of(new PolicyEvaluation.Match(ConsultationToolsTest.POLICY, List.of(release), "APPROVED_POLICY")), List.of(), null);
        AgentModelGateway gateway = (message, history, tools) -> {
            tools.rankConsultationChannels("CARD_LOSS", null, null, Map.of("requestKind", "RELEASE_LOSS"),
                    "GENERAL_GUIDANCE", null, null, null, null, null, null, null, null);
            return new GeneratedConsultation("카드 분실 해제 안내", "분실 해제 문의");
        };
        var service = new SpringAiAgentService(gateway, new StubPolicyService(policy),
                new StubChannelRankingService(rankedEvaluation()), memory);
        var result = service.chat(newSession(), "카드를 찾았어요");
        assertThat(result.responseType()).isEqualTo(ResponseType.CHANNEL_RECOMMENDATION);
        assertThat(result.actions()).isEmpty();
    }

    @Test
    void newIntrabankPolicyDoesNotExposeChatbotMenuForPhoneOnlyRanking() {
        var branch = new PolicyEvaluation.Branch("INTRABANK_AUTO_GUIDE", "APPLICABLE", List.of(),
                List.of(), List.of(), List.of(), List.of(), "WEB_CHECKED", "WOORI_BANK");
        var policy = new com.example.wonway.common.contract.PolicyResult(null, "INTRABANK_AUTO", "당행 자동이체", "{}", null, null);
        var evaluation = new PolicyEvaluation(PolicyEvaluation.Status.MATCHED,
                List.of(new PolicyEvaluation.Match(policy, List.of(branch), "DEMO_ONLY")), List.of(), null);
        AgentModelGateway gateway = (message, history, tools) -> {
            tools.rankConsultationChannels("INTRABANK_AUTO_TRANSFER", null, "INTRABANK_TRANSFER",
                    Map.of("transferType", "INTRABANK", "operation", "CHANGE_DATE"),
                    "SELF_SERVICE_GUIDANCE", false, true, false, false, true, false, null, null);
            return new GeneratedConsultation("당행 자동이체 메뉴를 확인해 주세요.", "당행 날짜 변경 안내");
        };
        var service = new SpringAiAgentService(gateway, new StubPolicyService(evaluation),
                new StubChannelRankingService(rankedEvaluation()), memory);
        var result = service.chat(newSession(), "우리은행 계좌끼리 자동이체 날짜 변경 방법");
        assertThat(result.responseType()).isEqualTo(ResponseType.CHANNEL_RECOMMENDATION);
        assertThat(result.actions()).as("PHONE recommendations do not expose chatbot mock menus").isEmpty();
    }
}
