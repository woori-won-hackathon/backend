package com.example.wonway.session.exception;

import java.util.UUID;

/**
 * 이미 종료된 세션에 요청한 경우입니다. HTTP 409로 변환됩니다.
 */
public class SessionClosedException extends RuntimeException {

    public SessionClosedException(UUID sessionId) {
        super("이미 종료된 세션입니다: " + sessionId);
    }
}
