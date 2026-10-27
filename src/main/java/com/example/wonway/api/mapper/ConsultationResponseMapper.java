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
                .map(recommendation -> toChannelResponse(recommendation, result))
                .toList();
        return new ConsultationMessageResponse(
                sessionId,
                result.answer(),
                recommendations,
                result.consultationSummary(),
                result.responseType(),
                result.actions(),
                result.guidance()
        );
    }

    private static ChannelRecommendationResponse toChannelResponse(ChannelRecommendation recommendation, AgentResult result) {
        return new ChannelRecommendationResponse(
                recommendation.channel().name(),
                recommendation.score(),
                recommendation.processable(),
                result.understanding() == null ? recommendation.reasons() : friendlyReasons(recommendation, result),
                recommendation.officialPageUrl(),
                1 + (int) result.recommendations().stream().filter(other -> other.score() > recommendation.score()).count()
        );
    }

    private static List<String> friendlyReasons(ChannelRecommendation recommendation, AgentResult result) {
        var reasons = new java.util.ArrayList<String>();
        reasons.add(switch (recommendation.channel().name()) {
            case "CHATBOT" -> "확인된 조건에서 이용 방법과 관련 메뉴를 대화로 안내받을 수 있어요.";
            case "PHONE" -> "확인된 문의에 대해 상담원과 통화하며 안내받을 수 있어요.";
            case "EMAIL" -> "확인된 조건에서 문의를 남기고 답변을 받아볼 수 있어요.";
            default -> "확인된 문의를 안내할 수 있는 채널이에요.";
        });
        var context = result.understanding().context();
        String channel = recommendation.channel().name();
        recommendation.reasons().stream().filter(reason -> reason.startsWith("고객안내: "))
                .map(reason -> reason.substring("고객안내: ".length())).forEach(reasons::add);
        if (channel.equals(context.get("preferredChannel"))) reasons.add("직접 말씀하신 상담 방식 선호를 반영했어요.");
        else if ("CHATBOT".equals(channel) && Boolean.TRUE.equals(context.get("selfServiceAvailable")))
            reasons.add("앱·인터넷뱅킹을 이용할 수 있다고 하셔서 관련 메뉴를 직접 확인하는 안내에 적합해요.");
        else if ("EMAIL".equals(channel) && Boolean.TRUE.equals(context.get("acceptsAsyncReply"))
                && Boolean.FALSE.equals(context.get("urgent")) && Boolean.FALSE.equals(context.get("realTimeConversationNeeded")))
            reasons.add("답변을 나중에 받아도 괜찮다는 조건을 반영했어요.");
        else if ("PHONE".equals(channel) && Boolean.TRUE.equals(context.get("realTimeConversationNeeded")))
            reasons.add("실시간으로 문답하고 싶다는 조건을 반영했어요.");
        if (recommendation.reasons().stream().anyMatch(reason -> reason.contains("개발용")))
            reasons.add("개발용 정책·모의 운영정보를 기준으로 한 추천이에요.");
        return List.copyOf(reasons);
    }
}
