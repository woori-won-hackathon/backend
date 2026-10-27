package com.example.wonway.agent;

import com.example.wonway.agent.model.ResponseType;
import com.example.wonway.common.contract.ChannelRecommendation;
import com.example.wonway.common.contract.ChannelType;
import com.example.wonway.policy.model.ChannelEvaluation;
import com.example.wonway.policy.model.PolicyEvaluation;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.example.wonway.agent.ConsultationToolsTest.*;
import static org.assertj.core.api.Assertions.assertThat;

class StagedConsultationTest {
    static StagedAgentModelGateway gateway(String... responses) {
        Queue<String> queue = new ArrayDeque<>(List.of(responses));
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) {
                // Interpretation must not give the model discretionary access to ranking tools.
                assertThat(prompt.getOptions()).isNotNull();
                return new ChatResponse(List.of(new Generation(new AssistantMessage(queue.remove()))));
            }
        };
        var beans = new StaticListableBeanFactory();
        beans.addBean("model", model);
        return new StagedAgentModelGateway(beans.getBeanProvider(ChatModel.class));
    }

    @Test void keepsConfirmedSlotsAcrossQuestionsAndSeparatesRecommendationFromProcedure() {
        var gateway = gateway("""
                {"intent":"AUTO_TRANSFER_MANAGEMENT","intentEvidence":"생활비 보내던 걸 26일로 바꾸고 싶어",
                 "facts":[{"name":"operation","value":"CHANGE_DATE","evidence":"26일로 바꾸고 싶어"}],
                 "context":[],"purpose":"생활비 자동이체 날짜 변경"}
                """, """
                {"intent":null,"facts":[{"name":"transferType","value":"INTERBANK","evidence":"타행"}],"context":[]}
                """, """
                {"intent":null,"facts":[],"context":[{"name":"selfServiceAvailable","value":"true","evidence":"앱 이용 가능"}]}
                """);
        var count = new AtomicInteger();
        var branch = new PolicyEvaluation.Branch("INTERBANK_AUTO_GUIDE", "APPLICABLE", List.of(),
                List.of("공식 자동이체 메뉴에서 등록 내역을 선택하세요."), List.of("적용 회차를 확인하세요."), List.of(), List.of(), "WEB_CHECKED", "WOORI_BANK");
        var policy = new PolicyEvaluation(PolicyEvaluation.Status.MATCHED,
                List.of(new PolicyEvaluation.Match(POLICY, List.of(branch), "DEMO_ONLY")), List.of(), null);
        var channels = new StubChannelRankingService(rankedEvaluation()) {
            public ChannelEvaluation evaluate(com.example.wonway.common.contract.ChannelRankingCondition condition) {
                count.incrementAndGet();
                assertThat(condition.facts()).containsEntry("transferType", "INTERBANK").containsEntry("operation", "CHANGE_DATE");
                assertThat(condition.context().requestGoal()).isEqualTo("SELF_SERVICE_GUIDANCE");
                assertThat(condition.context().needsAccountSpecificCheck()).isNull();
                assertThat(condition.context().urgent()).isNull();
                return new ChannelEvaluation("RANKED", List.of(new ChannelRecommendation(ChannelType.CHATBOT,
                        87, true, List.of("검증된 근거"), null)), List.of(), "test");
            }
        };
        var memory = new ConversationMemory();
        var id = UUID.randomUUID(); memory.open(id);
        var service = new SpringAiAgentService(gateway, new StubPolicyService(policy), channels, memory);
        var first = service.chat(id, "월급날이 바뀌어서 생활비 보내던 걸 26일로 바꾸고 싶어");
        assertThat(first.responseType()).isEqualTo(ResponseType.FOLLOW_UP);
        assertThat(first.answer()).contains("받는 계좌");
        assertThat(first.guidance()).isNull();
        var second = service.chat(id, "타행이야");
        assertThat(second.responseType()).isEqualTo(ResponseType.FOLLOW_UP);
        assertThat(second.answer()).contains("직접 이용");
        assertThat(count).hasValue(0);
        var last = service.chat(id, "앱 이용 가능");
        assertThat(last.responseType()).isEqualTo(ResponseType.CHANNEL_RECOMMENDATION);
        assertThat(last.answer()).doesNotContain("등록 내역을 선택");
        assertThat(last.consultationSummary()).isEqualTo("생활비 자동이체 날짜 변경");
        assertThat(last.guidance()).contains("공식 자동이체 메뉴", "적용 회차");
        assertThat(last.actions()).extracting(a -> a.menuId()).containsExactly("AUTO_TRANSFER");
        assertThat(count).hasValue(1);
    }

    @Test void ignoresUngroundedFactsAndPreservesOtherSlotsDuringBankCorrection() {
        var gateway = gateway();
        var before = new ConsultationUnderstanding("INTERBANK_AUTO_TRANSFER", Map.of("transferType", "INTERBANK", "operation", "CHANGE_DATE"),
                Map.of("selfServiceAvailable", true), "날짜 변경");
        var result = gateway.merge(before, new ConsultationUnderstanding.Extraction("INTRABANK_AUTO_TRANSFER", "당행", true,
                List.of(new ConsultationUnderstanding.Slot("transferType", "INTRABANK", "당행"),
                        new ConsultationUnderstanding.Slot("issuer", "WOORI_CARD", "없는 원문")),
                List.of(new ConsultationUnderstanding.Slot("needsAccountSpecificCheck", "true", "날짜 바꾸고 싶어")), "날짜 변경"),
                "아니 당행이야. 날짜 바꾸고 싶어");
        assertThat(result.intent()).isEqualTo("INTRABANK_AUTO_TRANSFER");
        assertThat(result.facts()).containsEntry("operation", "CHANGE_DATE").containsEntry("transferType", "INTRABANK").doesNotContainKey("issuer");
        assertThat(result.context()).containsEntry("selfServiceAvailable", true).doesNotContainKey("needsAccountSpecificCheck");
    }

    @Test void newTopicDropsPreviousBankingConditions() {
        var gateway = gateway();
        var before = new ConsultationUnderstanding("INTERBANK_AUTO_TRANSFER", Map.of("transferType", "INTERBANK"),
                Map.of("urgent", false), "자동이체");
        var after = gateway.merge(before, new ConsultationUnderstanding.Extraction("CARD_LOSS", "카드 잃어버렸어", true,
                List.of(), List.of(), "카드 분실"), "다른 건데 카드 잃어버렸어");
        assertThat(after.intent()).isEqualTo("CARD_LOSS");
        assertThat(after.facts()).isEmpty(); assertThat(after.context()).isEmpty();
    }

    @Test void parsesNumericPolicyFactsAndRejectsInvalidTypes() {
        var gateway = gateway();
        var result = gateway.merge(null, new ConsultationUnderstanding.Extraction(null, null, false,
                List.of(new ConsultationUnderstanding.Slot("age", "10", "10살"),
                        new ConsultationUnderstanding.Slot("unknownPayment", "yes", "모르는 결제")), List.of(), null),
                "10살이고 모르는 결제야");
        assertThat(result.facts().get("age")).isEqualTo(10L);
        assertThat(result.facts()).doesNotContainKey("unknownPayment");
    }

    @Test void asksForUnknownPreferredChannelContextButNeverAsksCustomersToConfirmBankAvailability() {
        String extraction = """
                {"intent":"CARD_LOSS","intentEvidence":"우리카드 분실","facts":[],
                 "context":[{"name":"preferredChannel","value":"EMAIL","evidence":"이메일"}],"purpose":"분실 안내"}
                """;
        var gateway = gateway(extraction, extraction);
        var unknownEmail = new ChannelEvaluation("RANKED", rankedEvaluation().recommendations(),
                List.of(new ChannelEvaluation.Decision(ChannelType.EMAIL, "UNKNOWN", null, null,
                        List.of("비긴급 여부·비동기 회신 수용·실시간 문답 필요 여부 미확인"), List.of())), "test");
        var firstTools = new ConsultationTools(new StubPolicyService(MATCHED), new StubChannelRankingService(unknownEmail));
        var followUp = gateway.generate("우리카드 분실 이메일 안내", List.of(), firstTools);
        assertThat(followUp.intentClarificationNeeded()).isTrue();
        assertThat(followUp.answer()).contains("급하게");
        var unavailable = new ChannelEvaluation("NO_ELIGIBLE_CHANNEL", List.of(),
                List.of(new ChannelEvaluation.Decision(ChannelType.EMAIL, "UNKNOWN", null, null,
                        List.of("지원 범위 또는 현재 운영 상태가 확인되지 않음"), List.of())), "test");
        var lastTools = new ConsultationTools(new StubPolicyService(MATCHED), new StubChannelRankingService(unavailable));
        assertThat(gateway.generate("우리카드 분실 이메일 안내", List.of(), lastTools).intentClarificationNeeded()).isFalse();
    }
}
