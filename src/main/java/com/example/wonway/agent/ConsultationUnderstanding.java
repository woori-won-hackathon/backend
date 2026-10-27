package com.example.wonway.agent;

import java.util.Map;
import org.springframework.ai.tool.annotation.ToolParam;

/** Only customer-supported slots survive into the next turn; never model-written procedures. */
public record ConsultationUnderstanding(String intent, Map<String, Object> facts, Map<String, Object> context,
                                        String purpose) {
    public ConsultationUnderstanding {
        facts = facts == null ? Map.of() : Map.copyOf(facts);
        context = context == null ? Map.of() : Map.copyOf(context);
    }
    public record Slot(String name, String value, String evidence) {}
    public record Extraction(@ToolParam(required = false) String intent,
                             @ToolParam(required = false) String intentEvidence,
                             @ToolParam(required = false) Boolean newTopic,
                             @ToolParam(required = false) java.util.List<Slot> facts,
                             @ToolParam(required = false) java.util.List<Slot> context,
                             @ToolParam(required = false) String purpose) {}
}
