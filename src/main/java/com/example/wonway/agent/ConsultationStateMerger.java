package com.example.wonway.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Validates evidence and merges customer state; never chooses tools or questions. */
final class ConsultationStateMerger {
    private final Set<String> intents = new HashSet<>();
    private final Set<String> factNames = new HashSet<>();
    private final Map<String, String> factTypes = new HashMap<>();
    private static final Set<String> CONTEXT = Set.of("selfServiceAvailable", "selfServiceAttemptFailed",
            "urgent", "acceptsAsyncReply", "realTimeConversationNeeded", "preferredChannel",
            "identityVerificationAvailable", "needsAccountSpecificCheck");

    ConsultationStateMerger() {
        try {
            JsonNode root = new ObjectMapper().readTree(AgentPromptCatalog.load());
            root.path("policies").forEach(policy -> intents.add(policy.path("intent").asText()));
            collectFields(root);
        } catch (Exception exception) { throw new IllegalStateException("Invalid intent catalog", exception); }
    }
    private void collectFields(JsonNode node) {
        if (node.isObject() && node.has("field")) {
            String field = node.path("field").asText();
            factNames.add(field);
            JsonNode value = node.path("value");
            if (value.isArray() && !value.isEmpty()) value = value.get(0);
            factTypes.put(field, value.isBoolean() ? "BOOLEAN" : value.isNumber() ? "NUMBER" : "STRING");
        }
        node.forEach(this::collectFields);
    }

    ConsultationUnderstanding merge(ConsultationUnderstanding before, ConsultationUnderstanding.Extraction extraction, String message) {
        boolean groundedIntent = extraction.intentEvidence() != null && !extraction.intentEvidence().isBlank()
                && message.contains(extraction.intentEvidence());
        String nextIntent = groundedIntent && (intents.contains(extraction.intent()) || "AUTO_TRANSFER_MANAGEMENT".equals(extraction.intent()))
                ? extraction.intent() : null;
        boolean sameTransfer = before != null && before.intent() != null && before.intent().contains("AUTO_TRANSFER")
                && nextIntent != null && nextIntent.contains("AUTO_TRANSFER");
        boolean changed = groundedIntent && !sameTransfer && (Boolean.TRUE.equals(extraction.newTopic())
                || nextIntent != null && before != null && before.intent() != null && !nextIntent.equals(before.intent()));
        Map<String, Object> facts = new HashMap<>(before == null || changed ? Map.of() : before.facts());
        Map<String, Object> context = new HashMap<>(before == null || changed ? Map.of() : before.context());
        apply(facts, extraction.facts(), factNames, message);
        apply(context, extraction.context(), CONTEXT, message);
        String intent = nextIntent != null ? nextIntent : before == null || changed ? null : before.intent();
        // Resolve the generic family only from a confirmed receiving-bank fact, including corrections.
        if (intent != null && (intent.contains("AUTO_TRANSFER") || "AUTO_TRANSFER_MANAGEMENT".equals(intent))) {
            intent = switch (String.valueOf(facts.get("transferType"))) {
                case "INTRABANK" -> "INTRABANK_AUTO_TRANSFER";
                case "INTERBANK" -> "INTERBANK_AUTO_TRANSFER";
                default -> "AUTO_TRANSFER_MANAGEMENT";
            };
        }
        String purpose = before != null && !changed ? before.purpose() : "문의하신 업무의 이용 방법";
        if (groundedIntent && extraction.purpose() != null && !extraction.purpose().isBlank()) {
            purpose = extraction.purpose().strip().replaceAll("[\\r\\n]+", " ");
            if (purpose.length() > 60) purpose = purpose.substring(0, 60);
        }
        return new ConsultationUnderstanding(intent, facts, context, purpose);
    }

    private void apply(Map<String, Object> target, List<ConsultationUnderstanding.Slot> slots,
                              Set<String> allowed, String message) {
        if (slots == null) return;
        for (var slot : slots) {
            if (slot == null || slot.name() == null || !allowed.contains(slot.name()) || slot.value() == null || slot.value().isBlank()
                    || slot.evidence() == null || slot.evidence().isBlank() || !message.contains(slot.evidence())) continue;
            String value = slot.value().strip();
            if (CONTEXT.contains(slot.name())) {
                if ("preferredChannel".equals(slot.name())) {
                    if ("NONE".equals(value)) target.remove(slot.name());
                    if (Set.of("CHATBOT", "PHONE", "EMAIL").contains(value)) target.put(slot.name(), value);
                } else if ("true".equals(value) || "false".equals(value)) {
                    // A wish to change a date is not evidence of an actual account lookup request.
                    if ("needsAccountSpecificCheck".equals(slot.name()) && "true".equals(value)
                            && !slot.evidence().matches("(?s).*(조회|잔액|거래내역|처리됐|적용됐|반영됐).*") ) continue;
                    target.put(slot.name(), Boolean.valueOf(value));
                }
            } else {
                // The policy evaluator requires typed values, especially ages/counts and Boolean facts.
                String type = factTypes.getOrDefault(slot.name(), "STRING");
                if ("BOOLEAN".equals(type)) {
                    if ("true".equals(value) || "false".equals(value)) target.put(slot.name(), Boolean.valueOf(value));
                } else if ("NUMBER".equals(type)) {
                    try { target.put(slot.name(), Long.valueOf(value)); } catch (NumberFormatException ignored) { /* ask again, do not send a malformed policy input */ }
                } else target.put(slot.name(), value);
            }
        }
    }

}
