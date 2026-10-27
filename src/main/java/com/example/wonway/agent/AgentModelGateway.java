package com.example.wonway.agent;

import com.example.wonway.agent.model.GeneratedConsultation;

/** Spring AI 호출 경계를 분리해 도구 오케스트레이션을 외부 API 없이 검증합니다. */
interface AgentModelGateway {

    GeneratedConsultation generate(String message, ConsultationTools tools);
}
