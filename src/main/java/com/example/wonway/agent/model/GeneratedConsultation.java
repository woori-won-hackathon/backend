package com.example.wonway.agent.model;

/** 검증된 도구 결과를 바탕으로 AI가 작성하는 문안만 담습니다. */
public record GeneratedConsultation(
        String answer,
        String consultationSummary,
        Boolean intentClarificationNeeded
) {

    public GeneratedConsultation(String answer, String consultationSummary) {
        this(answer, consultationSummary, false);
    }
}
