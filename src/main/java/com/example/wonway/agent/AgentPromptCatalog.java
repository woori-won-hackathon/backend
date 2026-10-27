package com.example.wonway.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

/** B 담당자가 제공한 입력 카탈로그를 공백 없는 JSON으로 모델에 전달합니다. */
final class AgentPromptCatalog {

    private static final String CATALOG_PATH = "policy/handoff/tool-input-catalog.json";

    private AgentPromptCatalog() {
    }

    static String load() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode catalog = mapper.readTree(new ClassPathResource(CATALOG_PATH).getInputStream());
            return mapper.writeValueAsString(catalog);
        } catch (IOException e) {
            throw new IllegalStateException("상담 도구 입력 카탈로그를 읽을 수 없습니다.", e);
        }
    }
}
