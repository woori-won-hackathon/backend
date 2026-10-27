package com.example.wonway.agent.exception;

/** 모델이 필수 도구 실행 또는 구조화 응답을 완료하지 못했을 때 발생합니다. */
public class AgentUnavailableException extends RuntimeException {

    public AgentUnavailableException(String message) {
        super(message);
    }
}
