package com.example.wonway.api;

import com.example.wonway.session.InMemoryConsultationSessionService;
import com.example.wonway.agent.ConversationMemory;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A의 AgentService 구현체가 아직 없을 때의 동작을 검증합니다.
 * 세션 생성·종료는 동작하고, 메시지 API만 503을 반환해야 합니다.
 */
@WebMvcTest(ConsultationController.class)
@Import({InMemoryConsultationSessionService.class, ConversationMemory.class, GlobalExceptionHandler.class})
class ConsultationControllerWithoutAgentTest {

    @Autowired
    MockMvc mvc;

    @Test
    void 에이전트가_없으면_메시지는_503이고_세션_API는_동작한다() throws Exception {
        String body = mvc.perform(post("/api/v1/consultations"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.sessionId");

        mvc.perform(post("/api/v1/consultations/{id}/messages", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"안녕\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AGENT_NOT_READY"));

        mvc.perform(delete("/api/v1/consultations/{id}", id)).andExpect(status().isNoContent());
    }
}
