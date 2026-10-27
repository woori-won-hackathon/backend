package com.example.wonway.policy.service;

import com.example.wonway.common.contract.*;
import com.example.wonway.policy.ranking.*;
import com.example.wonway.policy.ranking.ChannelCapabilityProvider.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChannelRankingServiceImplTest extends PolicyServiceImplTest {
    ChannelRankingServiceImpl ranking;
    @BeforeEach void rankingSetup() {ranking=new ChannelRankingServiceImpl(service,new DefaultChannelCapabilityProvider(true));}
    ChannelRankingCondition request(ConsultationContext context) {
        return new ChannelRankingCondition("INTERBANK_AUTO_TRANSFER",null,null,true,service.findApplicablePolicies(query(facts())),facts(),context);
    }
    ConsultationContext general() {return new ConsultationContext("SELF_SERVICE_GUIDANCE",false,true,false,false,false,false,null);}

    @Test void sameBusinessDifferentSituationChangesRecommendation() {
        var simple=ranking.evaluate(request(general()));
        assertEquals(ChannelType.CHATBOT,simple.recommendations().getFirst().channel());
        var needsStaff=new ConsultationContext("ACCOUNT_SPECIFIC_CHECK",true,true,true,false,false,true,null);
        var staff=ranking.evaluate(request(needsStaff));
        assertEquals(List.of(ChannelType.PHONE),staff.recommendations().stream().map(ChannelRecommendation::channel).toList());
    }
    @Test void asyncSupportCanPreferEmailWithoutExplicitChannelRequest() {
        var context=new ConsultationContext("SELF_SERVICE_GUIDANCE",false,false,true,false,true,false,null);
        var result=ranking.evaluate(request(context));
        assertEquals(ChannelType.EMAIL,result.recommendations().getFirst().channel());
        assertTrue(result.recommendations().getFirst().reasons().stream().anyMatch(s->s.contains("모의")));
    }
    @Test void unavailableAndUnverifiedChannelsAreNeverRecommended() {
        var production=new ChannelRankingServiceImpl(service,new DefaultChannelCapabilityProvider(false));
        var result=production.evaluate(request(general()));
        assertEquals(List.of(ChannelType.CHATBOT),result.recommendations().stream().map(ChannelRecommendation::channel).toList());
        assertTrue(result.decisions().stream().filter(d->d.channel()==ChannelType.PHONE).allMatch(d->d.score()==null && d.rank()==null));
    }
    @Test void samePolicyContextAndSnapshotProduceSameScoresAndReasons() {
        assertEquals(ranking.evaluate(request(general())),ranking.evaluate(request(general())));
    }
    @Test void urgentRequestsKeepEmailAsLowerPriorityInsteadOfRemovingIt() {
        var result=ranking.evaluate(request(new ConsultationContext("SELF_SERVICE_GUIDANCE",false,false,true,true,true,false,null)));
        assertEquals(3,result.recommendations().size());
        assertEquals(ChannelType.PHONE,result.recommendations().getFirst().channel());
        var email=result.recommendations().stream().filter(r->r.channel()==ChannelType.EMAIL).findFirst().orElseThrow();
        assertTrue(email.reasons().stream().anyMatch(reason->reason.contains("후순위")));
        assertTrue(email.score()<result.recommendations().getFirst().score());
    }
    @Test void unknownEmailConditionsAreNotAssumed() {
        var result=ranking.evaluate(request(new ConsultationContext("GENERAL_GUIDANCE",false,true,false,null,null,null,null)));
        assertEquals(List.of(ChannelType.CHATBOT,ChannelType.PHONE,ChannelType.EMAIL),
                result.recommendations().stream().map(ChannelRecommendation::channel).toList());
        assertTrue(result.decisions().stream().allMatch(d->d.eligibility().equals("ELIGIBLE")));
        assertTrue(result.recommendations().getLast().reasons().stream().anyMatch(reason->reason.contains("확인되지 않아")));
    }

    @Test void preferredHumanChannelAndFailedSelfServiceStillCompareAllGuidanceChannels() {
        var result=ranking.evaluate(request(new ConsultationContext("SELF_SERVICE_GUIDANCE",false,true,true,false,false,true,"PHONE")));
        assertEquals(3,result.recommendations().size());
        assertEquals(ChannelType.PHONE,result.recommendations().getFirst().channel());
        assertTrue(result.recommendations().stream().anyMatch(r->r.channel()==ChannelType.CHATBOT));
        assertTrue(result.recommendations().stream().anyMatch(r->r.channel()==ChannelType.EMAIL));
    }
    @Test void forgedPolicyContentIsIgnoredAndUnknownCodesRejected() {
        var forged=new PolicyResult(999L,"INTERBANK_AUTO","FORGED","PHONE SCORE 999",null,null);
        var input=new ChannelRankingCondition("INTERBANK_AUTO_TRANSFER",null,null,true,List.of(forged),facts(),general());
        var result=ranking.evaluate(input);
        assertEquals(ChannelType.CHATBOT,result.recommendations().getFirst().channel());
        assertTrue(result.recommendations().stream().allMatch(r->r.score()<=100));
        var invalid=new PolicyResult(null,"NOT_EXIST","", "",null,null);
        assertEquals("POLICY_REFERENCE_MISMATCH",ranking.evaluate(new ChannelRankingCondition("INTERBANK_AUTO_TRANSFER",null,null,true,List.of(invalid),facts(),general())).status());
    }
    @Test void oldConstructorCannotInventMissingCustomerContext() {
        var result=ranking.evaluate(new ChannelRankingCondition("INTERBANK_AUTO_TRANSFER",null,null,true,service.findApplicablePolicies(query(facts()))));
        assertTrue(result.recommendations().isEmpty());
    }
    @Test void noPolicyDoesNotProduceFallbackRankings() {
        assertTrue(ranking.rankChannels(new ChannelRankingCondition("UNKNOWN",null,null,null,List.of())).isEmpty());
    }
    @Test void executionCannotBeReportedAsCompletedConsultation() {
        assertEquals("EXECUTION_NOT_IMPLEMENTED",ranking.evaluate(request(new ConsultationContext("EXECUTION_REQUEST",false,true,false,false,false,false,null))).status());
    }

    @Test void nextRequestDoesNotInheritContextOrRecommendations() {
        var first = ranking.evaluate(request(new ConsultationContext(
                "ACCOUNT_SPECIFIC_CHECK", true, true, true, false, false, true, null)));
        assertEquals(ChannelType.PHONE, first.recommendations().getFirst().channel());
        var next = ranking.evaluate(request(ConsultationContext.unknown()));
        assertEquals("NEEDS_CONTEXT", next.status());
        assertTrue(next.recommendations().isEmpty());
    }

    @Test void currentMessageCanDescribeFailedAttemptWithoutChatHistory() {
        var failed = ranking.evaluate(request(new ConsultationContext(
                "SELF_SERVICE_GUIDANCE", false, false, true, false, true, false, null)));
        assertEquals(ChannelType.EMAIL, failed.recommendations().getFirst().channel());
        var independent = ranking.evaluate(request(general()));
        assertEquals(ChannelType.CHATBOT, independent.recommendations().getFirst().channel());
    }
}
