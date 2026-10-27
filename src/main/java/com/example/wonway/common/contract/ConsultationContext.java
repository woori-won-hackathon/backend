package com.example.wonway.common.contract;

/** 고객 메시지에서 파악한 상황. 미언급 Boolean은 null이며 서버 확인 사실과 구분한다. */
public record ConsultationContext(
        String requestGoal,
        Boolean needsAccountSpecificCheck,
        Boolean selfServiceAvailable,
        Boolean selfServiceAttemptFailed,
        Boolean urgent,
        Boolean acceptsAsyncReply,
        Boolean realTimeConversationNeeded,
        String preferredChannel
) {
    public static ConsultationContext unknown() {
        return new ConsultationContext(null,null,null,null,null,null,null,null);
    }
}
