package com.example.wonway.api.dto.response;

import java.util.List;
import java.util.UUID;
import com.example.wonway.agent.model.ResponseType;
import com.example.wonway.agent.model.BusinessAction;

public record ConsultationMessageResponse(
        UUID sessionId,
        String answer,
        List<ChannelRecommendationResponse> recommendations,
        String consultationSummary,
        ResponseType responseType,
        List<BusinessAction> actions,
        String guidance
) {
    public ConsultationMessageResponse {
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        actions = actions == null ? List.of() : List.copyOf(actions);
    }
    public ConsultationMessageResponse(UUID sessionId, String answer, List<ChannelRecommendationResponse> recommendations,
                                       String consultationSummary, ResponseType responseType, List<BusinessAction> actions) {
        this(sessionId, answer, recommendations, consultationSummary, responseType, actions, null);
    }
}
