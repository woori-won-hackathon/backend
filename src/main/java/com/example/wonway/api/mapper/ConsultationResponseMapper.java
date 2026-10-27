package com.example.wonway.api.mapper;

import com.example.wonway.agent.model.AgentResult;
import com.example.wonway.api.dto.response.ChannelRecommendationResponse;
import com.example.wonway.api.dto.response.ConsultationMessageResponse;
import com.example.wonway.common.contract.ChannelRecommendation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A가 반환한 AgentResult를 HTTP 응답 DTO로 변환합니다.
 */
public final class ConsultationResponseMapper {

    private ConsultationResponseMapper() {
    }

    public static ConsultationMessageResponse toMessageResponse(UUID sessionId, AgentResult result) {
        List<ChannelRecommendationResponse> recommendations = result.recommendations().stream()
                .filter(Objects::nonNull)
                // 처리 불가 채널은 사용자에게 추천하지 않는다 (A/B 검증에 더한 마지막 방어선, 팀 합의 필요).
                .filter(ChannelRecommendation::processable)
                .filter(recommendation -> recommendation.channel() != null)
                .map(ConsultationResponseMapper::toChannelResponse)
                .toList();
        return new ConsultationMessageResponse(
                sessionId,
                result.answer(),
                recommendations,
                result.consultationSummary()
        );
    }

    private static ChannelRecommendationResponse toChannelResponse(ChannelRecommendation recommendation) {
        return new ChannelRecommendationResponse(
                recommendation.channel().name(),
                recommendation.score(),
                recommendation.reasons(),
                recommendation.officialPageUrl()
        );
    }
}
