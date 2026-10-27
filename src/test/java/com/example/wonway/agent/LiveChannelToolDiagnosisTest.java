package com.example.wonway.agent;

import com.example.wonway.common.contract.ChannelRankingCondition;
import com.example.wonway.common.contract.ChannelRecommendation;
import com.example.wonway.policy.model.ChannelEvaluation;
import com.example.wonway.policy.service.ChannelRankingService;
import com.example.wonway.policy.service.PolicyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.example.wonway.agent.model.ResponseType;
import com.example.wonway.common.contract.ChannelType;
import com.example.wonway.session.ConsultationSessionService;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in real model diagnosis. Reads policy data but never changes DB contents or performs banking actions. */
@EnabledIfEnvironmentVariable(named = "WONWAY_LIVE_DIAGNOSIS", matches = "true")
@SpringBootTest(properties = {
        "spring.main.web-application-type=none", "spring.profiles.active=policy-demo",
        "policy.seed.enabled=false", "spring.jpa.hibernate.ddl-auto=none", "spring.sql.init.mode=never"
})
class LiveChannelToolDiagnosisTest {
    @Autowired AgentModelGateway model;
    @Autowired PolicyService policies;
    @Autowired ChannelRankingService channels;
    @Autowired AgentService agent;
    @Autowired ConsultationSessionService sessions;

    @Test
    void naturalLanguageCollectsOnlyMissingInformationThenRecommends() {
        var id = sessions.createSession();
        try {
            var first = agent.chat(id, "월급날이 25일로 바뀌어서, 엄마한테 매달 20일에 생활비 보내던 걸 26일로 바꾸고 싶어.");
            assertThat(first.responseType()).isEqualTo(ResponseType.FOLLOW_UP);
            assertThat(first.recommendations()).isEmpty();
            var second = agent.chat(id, "우리은행 계좌에서 엄마의 우리은행 계좌로 보내는 당행 자동이체야");
            assertThat(second.responseType()).isEqualTo(ResponseType.FOLLOW_UP);
            var last = agent.chat(id, "응 앱 사용할 수 있어");
            assertThat(last.responseType()).isEqualTo(ResponseType.CHANNEL_RECOMMENDATION);
            assertThat(last.recommendations()).extracting(r -> r.channel())
                    .containsExactly(ChannelType.CHATBOT, ChannelType.PHONE, ChannelType.EMAIL);
            assertThat(last.actions()).extracting(a -> a.menuId()).containsExactly("AUTO_TRANSFER");
            assertThat(last.guidance()).isNotBlank();
            assertThat(last.consultationSummary().length()).isLessThanOrEqualTo(60);
            System.out.println("NATURAL_FLOW_VERIFIED channels=" + last.recommendations().stream().map(r -> r.channel().name()).toList());
        } finally { sessions.closeSession(id); }
    }

    @Test
    void explicitGuidanceOnlyScenarioReturnsRankedChannelsAndDemoMenu() {
        var id = sessions.createSession();
        try {
            var result = agent.chat(id,
                    "우리은행에서 다른 은행으로 보내는 타행 자동이체 날짜를 매월 20일에서 26일로 바꾸는 방법과 메뉴를 안내해 주세요. "
                    + "실제 계좌 조회나 변경 실행은 요청하지 않아요. 모바일뱅킹은 이용할 수 있고 아직 변경을 시도하지 않았어요. "
                    + "긴급하지 않고, 이메일처럼 나중에 답변받는 것도 괜찮으며 실시간 상담은 필요하지 않아요. 채널 선호는 없어요.");
            // A real model may legitimately ask for clarification. Verify the conversational flow,
            // rather than requiring an identical first-turn answer from a nondeterministic model.
            int turns = 1;
            while (result.responseType() == ResponseType.FOLLOW_UP && turns < 3) {
                result = agent.chat(id,
                        "이미 등록된 타행 자동이체의 출금일을 20일에서 26일로 바꾸는 방법만 궁금해요. "
                        + "타행 자동이체이며 요청 작업은 날짜 변경입니다. 실제 계좌 조회나 변경 실행은 요청하지 않아요. "
                        + "모바일뱅킹 이용 가능, 변경 시도 실패 이력 없음, 긴급하지 않음, 비동기 이메일 답변 수용, 실시간 상담 불필요입니다.");
                turns++;
            }
            System.out.println("LIVE_SUCCESS_SCENARIO responseType=" + result.responseType()
                    + " turns=" + turns
                    + " channels=" + result.recommendations().stream().map(r -> r.channel().name()).toList()
                    + " menus=" + result.actions().stream().map(a -> a.menuId()).toList());
            assertThat(result.responseType()).isEqualTo(ResponseType.CHANNEL_RECOMMENDATION);
            assertThat(result.understanding().context()).containsEntry("selfServiceAvailable", true)
                    .containsEntry("urgent", false).containsEntry("acceptsAsyncReply", true)
                    .containsEntry("realTimeConversationNeeded", false);
            assertThat(result.recommendations()).extracting(r -> r.channel())
                    .containsExactly(ChannelType.CHATBOT, ChannelType.EMAIL, ChannelType.PHONE);
            assertThat(result.actions()).extracting(a -> a.menuId()).containsExactly("AUTO_TRANSFER");
        } finally {
            sessions.closeSession(id);
        }
    }

    @Test
    void reportsWhetherModelInvokesChannelToolAndWhatItReturns() {
        AtomicReference<ChannelRankingCondition> input = new AtomicReference<>();
        ChannelRankingService observedChannels = new ChannelRankingService() {
            @Override public List<ChannelRecommendation> rankChannels(ChannelRankingCondition condition) {
                return evaluate(condition).recommendations();
            }
            @Override public ChannelEvaluation evaluate(ChannelRankingCondition condition) {
                input.set(condition);
                return channels.evaluate(condition);
            }
        };
        String initial = "월급날이 25일로 바뀌어서, 엄마한테 매달 20일에 생활비 보내던 걸 26일로 바꾸고 싶어.";
        var firstTools = new ConsultationTools(policies, observedChannels);
        var first = model.generate(initial, List.of(), firstTools);
        report(1, firstTools, input.getAndSet(null));
        var secondTools = new ConsultationTools(policies, observedChannels);
        model.generate("타행이야", List.of(new ConversationMemory.Turn(initial, first.answer(), firstTools.understanding())), secondTools);
        report(2, secondTools, input.get());
    }

    private static void report(int turn, ConsultationTools tools, ChannelRankingCondition condition) {
        var policy = tools.lastPolicyEvaluation();
        var result = tools.lastChannelEvaluation();
        // No customer content, extracted facts or model prose is printed.
        System.out.println("LIVE_CHANNEL_DIAGNOSIS turn=" + turn
                + " policy=" + (policy == null ? "NOT_CALLED" : policy.status())
                + " channel=" + (result == null ? "NOT_CALLED" : result.status())
                + " requestGoal=" + (condition == null ? "NOT_PASSED" : condition.context().requestGoal())
                + " recommendations=" + (result == null ? 0 : result.recommendations().size()));
        if (result != null) for (var decision : result.decisions()) {
            System.out.println("LIVE_CHANNEL_EXCLUSION turn=" + turn + " channel=" + decision.channel()
                    + " eligibility=" + decision.eligibility() + " reasons=" + decision.reasons());
        }
    }
}
