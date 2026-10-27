package com.example.wonway.agent.model;

import com.example.wonway.common.contract.ChannelRecommendation;

import java.util.List;

public record AgentResult(
        String answer,
        List<ChannelRecommendation> recommendations,
        String consultationSummary,
        ResponseType responseType,
        List<BusinessAction> actions,
        String guidance,
        com.example.wonway.agent.ConsultationUnderstanding understanding
) {
    public AgentResult {
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        actions = actions == null ? List.of() : List.copyOf(actions);
        java.util.Objects.requireNonNull(responseType, "responseType");
    }

    public AgentResult(String answer, List<ChannelRecommendation> recommendations, String summary,
                       ResponseType type, List<BusinessAction> actions) {
        this(answer, recommendations, summary, type, actions, null, null);
    }

    public AgentResult(String answer, List<ChannelRecommendation> recommendations, String summary) {
        this(answer, recommendations, summary,
                recommendations != null && !recommendations.isEmpty()
                        ? ResponseType.CHANNEL_RECOMMENDATION : ResponseType.GUIDANCE, List.of());
    }
}
