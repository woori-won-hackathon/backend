package com.example.wonway.api;

import com.example.wonway.agent.AgentService;
import com.example.wonway.api.config.CorsConfig;
import com.example.wonway.session.InMemoryConsultationSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 브라우저가 보내는 사전 요청(preflight)과 실제 요청에 대해 CORS 허용 여부를 검증합니다.
 */
@WebMvcTest(ConsultationController.class)
@Import({InMemoryConsultationSessionService.class, GlobalExceptionHandler.class, CorsConfig.class})
class CorsConfigTest {

    private static final String FRONT = "http://localhost:5173";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AgentService agentService;

    @Test
    void 프런트_개발_서버의_사전_요청은_허용된다() throws Exception {
        mvc.perform(options("/api/v1/consultations/{id}", "00000000-0000-0000-0000-000000000000")
                        .header(HttpHeaders.ORIGIN, FRONT)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "DELETE"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONT));
    }

    @Test
    void 프런트_개발_서버의_실제_요청에_허용_헤더가_붙는다() throws Exception {
        mvc.perform(post("/api/v1/consultations").header(HttpHeaders.ORIGIN, FRONT))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONT));
    }

    @Test
    void 허용하지_않은_주소는_거절된다() throws Exception {
        mvc.perform(options("/api/v1/consultations")
                        .header(HttpHeaders.ORIGIN, "http://evil.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }
}
