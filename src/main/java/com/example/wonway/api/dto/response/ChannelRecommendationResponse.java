package com.example.wonway.api.dto.response;

import java.util.List;

public record ChannelRecommendationResponse(
        String channel,
        int score,
        boolean processable,
        List<String> reasons,
        String officialPageUrl,
        Integer rank
) {
    public ChannelRecommendationResponse {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
    public ChannelRecommendationResponse(String channel, int score, boolean processable, List<String> reasons, String url) {
        this(channel, score, processable, reasons, url, null);
    }
}
