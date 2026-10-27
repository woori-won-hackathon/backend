package com.example.wonway.agent;

import java.util.Map;

/** Only customer-supported slots survive into the next turn; never model-written procedures. */
public record ConsultationUnderstanding(String intent, Map<String, Object> facts, Map<String, Object> context,
                                        String purpose) {
    public ConsultationUnderstanding {
        facts = facts == null ? Map.of() : Map.copyOf(facts);
        context = context == null ? Map.of() : Map.copyOf(context);
    }
    public record Slot(String name, String value, String evidence) {}
    public record Extraction(String intent, String intentEvidence, Boolean newTopic,
                             java.util.List<Slot> facts, java.util.List<Slot> context, String purpose) {}
}
