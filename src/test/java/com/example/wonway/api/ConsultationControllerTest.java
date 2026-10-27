package com.example.wonway.api;

import com.example.wonway.agent.AgentService;
import com.example.wonway.agent.model.AgentResult;
import com.example.wonway.common.contract.ChannelRecommendation;
import com.example.wonway.common.contract.ChannelType;
import com.example.wonway.session.InMemoryConsultationSessionService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 세션 서비스와 가짜(Mock) AgentService로 HTTP 계약을 검증합니다.
 */
@WebMvcTest(ConsultationController.class)
@Import({InMemoryConsultationSessionService.class, GlobalExceptionHandler.class})
class ConsultationControllerTest {

    private static final String BASE = "/api/v1/consultations";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AgentService agentService;

    @Test // T1: 시작 → 메시지 → 추가 메시지 → 종료
    void 상담_전체_수명주기() throws Exception {
        UUID id = startSession();
        given(agentService.chat(eq(id), any())).willReturn(result("안내입니다"));

        sendMessage(id, "카드를 잃어버렸어요")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(id.toString()))
                .andExpect(jsonPath("$.answer").value("안내입니다"))
                .andExpect(jsonPath("$.consultationSummary").value("요약"));
        sendMessage(id, "해외에서 결제됐어요").andExpect(status().isOk());

        mvc.perform(delete(BASE + "/{id}", id)).andExpect(status().isNoContent());
    }

    @Test // T8
    void 종료_후_메시지는_409이고_에이전트를_호출하지_않는다() throws Exception {
        UUID id = startSession();
        mvc.perform(delete(BASE + "/{id}", id)).andExpect(status().isNoContent());

        sendMessage(id, "안녕하세요")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_CLOSED"))
                .andExpect(jsonPath("$.path").value(BASE + "/" + id + "/messages"));
        verify(agentService, never()).chat(any(), any());
    }

    @Test
    void 종료를_두_번_하면_두번째는_409() throws Exception {
        UUID id = startSession();
        mvc.perform(delete(BASE + "/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(delete(BASE + "/{id}", id)).andExpect(status().isConflict());
    }

    @Test // T3: 각 세션의 id가 그대로 에이전트에 전달되고, 한쪽 종료가 다른 쪽에 영향을 주지 않는다
    void 두_세션은_각자의_id로_호출되고_서로_영향이_없다() throws Exception {
        UUID a = startSession();
        UUID b = startSession();
        given(agentService.chat(eq(a), any())).willReturn(result("A의 답"));
        given(agentService.chat(eq(b), any())).willReturn(result("B의 답"));

        sendMessage(a, "질문").andExpect(jsonPath("$.answer").value("A의 답"));
        sendMessage(b, "질문").andExpect(jsonPath("$.answer").value("B의 답"));

        mvc.perform(delete(BASE + "/{id}", a)).andExpect(status().isNoContent());
        sendMessage(b, "이어서").andExpect(status().isOk());
    }

    @Test
    void 존재하지_않는_세션은_404() throws Exception {
        sendMessage(UUID.randomUUID(), "안녕")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
        mvc.perform(delete(BASE + "/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void 잘못된_입력은_400이고_에이전트를_호출하지_않는다() throws Exception {
        UUID id = startSession();
        String tooLong = "가".repeat(1001);
        for (String body : new String[]{json("   "), json(""), json(tooLong), "{}", "JSON 아님"}) {
            mvc.perform(post(BASE + "/{id}/messages", id)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        mvc.perform(post(BASE + "/{id}/messages", "uuid-아님")
                        .contentType(MediaType.APPLICATION_JSON).content(json("안녕")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SESSION_ID"));
        verify(agentService, never()).chat(any(), any());
    }

    @Test // T7은 A의 장애 예외 타입 합의 후 503 검증으로 바꾼다. 지금은 원인을 숨긴 500인지, 세션이 살아 있는지 확인한다.
    void 에이전트_오류는_500이고_내부_원인과_원문을_노출하지_않는다() throws Exception {
        UUID id = startSession();
        given(agentService.chat(eq(id), any())).willThrow(new IllegalStateException("timeout key=sk-secret"));

        String body = sendMessage(id, "카드 분실")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("sk-secret", "카드 분실");

        given(agentService.chat(eq(id), any())).willReturn(result("복구됨"));
        sendMessage(id, "다시 시도").andExpect(status().isOk());
    }

    @Test
    void 없는_경로는_500이_아니라_404() throws Exception {
        mvc.perform(post("/api/v1/unknown")).andExpect(status().isNotFound());
    }

    @Test
    void 처리_불가_채널은_응답에서_제외된다() throws Exception {
        UUID id = startSession();
        given(agentService.chat(eq(id), any())).willReturn(new AgentResult("답", List.of(
                new ChannelRecommendation(ChannelType.MOBILE_APP, 80, true, List.of("비대면 가능"), "https://example.com/app"),
                new ChannelRecommendation(ChannelType.BRANCH, 95, false, List.of("영업시간 외"), null)
        ), "요약"));

        sendMessage(id, "질문")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendations.length()").value(1))
                .andExpect(jsonPath("$.recommendations[0].channel").value("MOBILE_APP"));
    }

    private UUID startSession() throws Exception {
        String body = mvc.perform(post(BASE))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.sessionId"));
    }

    private ResultActions sendMessage(UUID id, String message) throws Exception {
        return mvc.perform(post(BASE + "/{id}/messages", id)
                .contentType(MediaType.APPLICATION_JSON).content(json(message)));
    }

    private static String json(String message) {
        return "{\"message\":\"" + message + "\"}";
    }

    private static AgentResult result(String answer) {
        return new AgentResult(answer, List.of(), "요약");
    }
}
