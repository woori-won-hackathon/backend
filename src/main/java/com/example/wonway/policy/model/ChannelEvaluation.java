package com.example.wonway.policy.model;

import com.example.wonway.common.contract.*;
import java.util.List;

public record ChannelEvaluation(String status,List<ChannelRecommendation> recommendations,
                                List<Decision> decisions,String scoringVersion) {
    public record Decision(ChannelType channel,String eligibility,Integer score,Integer rank,
                           List<String> reasons,List<String> policyCodes) {}
}
