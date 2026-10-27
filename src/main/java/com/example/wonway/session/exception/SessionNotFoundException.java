package com.example.wonway.session.exception;

import java.util.UUID;

/**
 * 등록된 적 없는 sessionId로 요청한 경우입니다. HTTP 404로 변환됩니다.
 */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(UUID sessionId) {
        super("세션을 찾을 수 없습니다: " + sessionId);
    }
}
