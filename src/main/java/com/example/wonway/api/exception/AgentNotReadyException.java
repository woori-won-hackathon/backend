package com.example.wonway.api.exception;

/**
 * AgentService 구현체가 아직 등록되지 않은 경우입니다. HTTP 503으로 변환됩니다.
 */
public class AgentNotReadyException extends RuntimeException {

    public AgentNotReadyException() {
        super("AgentService 구현체가 등록되지 않았습니다.");
    }
}
