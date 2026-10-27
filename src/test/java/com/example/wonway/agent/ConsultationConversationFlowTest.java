package com.example.wonway.agent;

import com.example.wonway.api.ConsultationController;
import com.example.wonway.api.GlobalExceptionHandler;
import com.example.wonway.agent.model.GeneratedConsultation;
import com.example.wonway.common.contract.PolicyResult;
import com.example.wonway.common.contract.PolicySearchCondition;
import com.example.wonway.common.contract.ChannelRankingCondition;
import com.example.wonway.policy.model.PolicyEvaluation;
import com.example.wonway.policy.model.ChannelEvaluation;
import com.example.wonway.session.InMemoryConsultationSessionService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.example.wonway.agent.ConsultationToolsTest.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real controller, agent, memory and tool orchestration with deterministic model/policy boundaries. */
class ConsultationConversationFlowTest {
    @Test
    void asksInSameConversationThenRanksAndSeparatesGuidanceFromPhoneSelection() throws Exception {
        var policy = new PolicyResult(null, "INTERBANK_AUTO", "타행 자동이체", "{}", null, null);
        var branch = new PolicyEvaluation.Branch("INTERBANK_AUTO_GUIDE", "APPLICABLE", List.of(),
                List.of("등록 내역 확인"), List.of(), List.of(), List.of(), "WEB_CHECKED", "WOORI_BANK");
        var matched = new PolicyEvaluation(PolicyEvaluation.Status.MATCHED,
                List.of(new PolicyEvaluation.Match(policy, List.of(branch), "DEMO_ONLY")), List.of(), null);
        var needsInfo = new PolicyEvaluation(PolicyEvaluation.Status.NEEDS_INFORMATION,
                List.of(), List.of("transferType"), null);
        var policies = new StubPolicyService(matched) {
            @Override public PolicyEvaluation evaluate(PolicySearchCondition condition) {
                return "INTERBANK".equals(condition.facts().get("transferType")) ? matched : needsInfo;
            }
        };
        var rankingCalls = new AtomicInteger();
        var channels = new StubChannelRankingService(rankedEvaluation()) {
            @Override public ChannelEvaluation evaluate(ChannelRankingCondition condition) {
                rankingCalls.incrementAndGet();
                assertThat(condition.facts()).containsEntry("transferType", "INTERBANK");
                return rankedEvaluation();
            }
        };
        AgentModelGateway model = (message, history, tools) -> {
            if (history.isEmpty()) {
                tools.findApplicablePolicies("INTERBANK_AUTO_TRANSFER", null, null, null, Map.of());
                return new GeneratedConsultation("받는 계좌가 다른 은행인가요?", "이체 대상 은행 확인 중");
            }
            assertThat(history.getFirst().user()).isEqualTo("생활비 이체일을 바꿀래요");
            assertThat(message).isEqualTo("네, 다른 은행이에요");
            tools.rankConsultationChannels("INTERBANK_AUTO_TRANSFER", null, null,
                    Map.of("transferType", "INTERBANK", "operation", "CHANGE_DATE"),
                    "SELF_SERVICE_GUIDANCE", null, null, null, null, null, null, null, null);
            return new GeneratedConsultation("자동이체 메뉴에서 등록 내역을 확인해 주세요.", "타행 생활비 이체일 변경 안내");
        };
        var memory = new ConversationMemory();
        var sessions = new InMemoryConsultationSessionService(memory);
        var agent = new SpringAiAgentService(model, policies, channels, memory);
        var beans = new StaticListableBeanFactory();
        beans.addBean("agent", agent);
        var mvc = MockMvcBuilders.standaloneSetup(new ConsultationController(sessions,
                beans.getBeanProvider(AgentService.class))).setControllerAdvice(new GlobalExceptionHandler()).build();
        String created = mvc.perform(post("/api/v1/consultations")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.sessionId");
        mvc.perform(post("/api/v1/consultations/{id}/messages", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"생활비 이체일을 바꿀래요\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.responseType").value("FOLLOW_UP"))
                .andExpect(jsonPath("$.actions").isEmpty()).andExpect(jsonPath("$.recommendations").isEmpty());
        assertThat(rankingCalls).hasValue(0);
        mvc.perform(post("/api/v1/consultations/{id}/messages", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"네, 다른 은행이에요\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.responseType").value("CHANNEL_RECOMMENDATION"))
                .andExpect(jsonPath("$.sessionId").value(id))
                .andExpect(jsonPath("$.recommendations[0].processable").value(true))
                .andExpect(jsonPath("$.guidance").value("등록 내역 확인"))
                .andExpect(jsonPath("$.actions").isEmpty());
        assertThat(rankingCalls).hasValue(1);
        mvc.perform(delete("/api/v1/consultations/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/consultations/{id}/messages", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"이어서\"}"))
                .andExpect(status().isConflict());
    }
}
