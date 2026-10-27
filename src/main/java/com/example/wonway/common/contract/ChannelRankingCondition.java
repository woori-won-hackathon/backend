package com.example.wonway.common.contract;

import java.util.*;

public record ChannelRankingCondition(
        String intent, String customerType, String productType,
        Boolean identityVerificationAvailable, List<PolicyResult> policies,
        Map<String,Object> facts, ConsultationContext context
) {
    public ChannelRankingCondition {
        policies = policies == null ? List.of() : List.copyOf(policies);
        facts = facts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(facts));
        context = context == null ? ConsultationContext.unknown() : context;
    }
    public ChannelRankingCondition(String intent,String customerType,String productType,Boolean identityVerificationAvailable,List<PolicyResult> policies) {
        this(intent,customerType,productType,identityVerificationAvailable,policies,Map.of(),ConsultationContext.unknown());
    }
}
