package com.example.wonway.common.contract;

import java.util.*;

public record PolicySearchCondition(
        String intent, String customerType, String productType,
        Boolean requiresIdentityVerification, Map<String,Object> facts
) {
    public PolicySearchCondition {
        facts = facts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(facts));
    }
    public PolicySearchCondition(String intent,String customerType,String productType,Boolean requiresIdentityVerification) {
        this(intent,customerType,productType,requiresIdentityVerification,Map.of());
    }
}
