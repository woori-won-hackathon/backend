package com.example.wonway.agent;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ConsultationStateMergerTest {
    @Test void unsupportedNewTopicClearsPreviousConfirmedConditions() {
        var before = new ConsultationUnderstanding("CARD_LOSS", Map.of("issuer", "WOORI_CARD"), Map.of("urgent", true), "카드 분실");
        var result = new ConsultationStateMerger().merge(before,
                new ConsultationUnderstanding.Extraction(null, "다른 문의", true, List.of(), List.of(), null), "다른 문의야");
        assertThat(result.intent()).isNull();
        assertThat(result.facts()).isEmpty();
        assertThat(result.context()).isEmpty();
    }
    @Test void ignoresUngroundedFactsAndPreservesOtherSlotsDuringBankCorrection() {
        var gateway = new ConsultationStateMerger();
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
        var gateway = new ConsultationStateMerger();
        var before = new ConsultationUnderstanding("INTERBANK_AUTO_TRANSFER", Map.of("transferType", "INTERBANK"),
                Map.of("urgent", false), "자동이체");
        var after = gateway.merge(before, new ConsultationUnderstanding.Extraction("CARD_LOSS", "카드 잃어버렸어", true,
                List.of(), List.of(), "카드 분실"), "다른 건데 카드 잃어버렸어");
        assertThat(after.intent()).isEqualTo("CARD_LOSS");
        assertThat(after.facts()).isEmpty(); assertThat(after.context()).isEmpty();
    }

    @Test void parsesNumericPolicyFactsAndRejectsInvalidTypes() {
        var gateway = new ConsultationStateMerger();
        var result = gateway.merge(null, new ConsultationUnderstanding.Extraction(null, null, false,
                List.of(new ConsultationUnderstanding.Slot("age", "10", "10살"),
                        new ConsultationUnderstanding.Slot("unknownPayment", "yes", "모르는 결제")), List.of(), null),
                "10살이고 모르는 결제야");
        assertThat(result.facts().get("age")).isEqualTo(10L);
        assertThat(result.facts()).doesNotContainKey("unknownPayment");
    }

}
