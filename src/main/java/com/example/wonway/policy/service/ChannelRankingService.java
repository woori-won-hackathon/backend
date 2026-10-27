package com.example.wonway.policy.service;

import com.example.wonway.common.contract.ChannelRankingCondition;
import com.example.wonway.common.contract.ChannelRecommendation;
import com.example.wonway.policy.model.ChannelEvaluation;
import java.util.List;

/** 담당 B: 검증된 처리 가능 채널만 평가하며 고정 업무별 순위를 사용하지 않는다. */
public interface ChannelRankingService {
    // 입력은 현재 메시지에서 추출한 값만 사용한다. 이전 요청의 사실을 보충하지 않는다.
    List<ChannelRecommendation> rankChannels(ChannelRankingCondition condition);
    /** 추천뿐 아니라 제외·미확인 사유와 공동 순위도 제공한다. */
    ChannelEvaluation evaluate(ChannelRankingCondition condition);
}
