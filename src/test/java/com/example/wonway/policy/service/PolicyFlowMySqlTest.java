package com.example.wonway.policy.service;

import com.example.wonway.common.contract.*;
import com.example.wonway.policy.model.PolicyEvaluation;
import com.example.wonway.policy.persistence.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="POLICY_MYSQL_VERIFY",matches="true")
@SpringBootTest(properties={
    "spring.main.web-application-type=none", "policy.demo-mode=true", "policy.channel-demo-mode=true",
    "policy.seed.enabled=false", "spring.ai.model.chat=none", "spring.ai.model.embedding=none",
    "spring.ai.model.image=none", "spring.ai.model.audio.transcription=none", "spring.ai.model.audio.speech=none",
    "spring.ai.model.moderation=none"
})
class PolicyFlowMySqlTest {
    @Autowired PolicyService policyService;
    @Autowired ChannelRankingService rankingService;
    @Autowired PolicyKnowledgeRepository repository;
    @Autowired EntityManager em;

    @Test void databasePoliciesFlowThroughBothSpringServices() {
        var facts=Map.<String,Object>of("transferType","INTERBANK","operation","CHANGE_DATE");
        var search=new PolicySearchCondition("INTERBANK_AUTO_TRANSFER","INDIVIDUAL","INTERBANK_TRANSFER",null,facts);
        var evaluation=policyService.evaluate(search);
        assertEquals(PolicyEvaluation.Status.MATCHED,evaluation.status());
        var general=new ConsultationContext("SELF_SERVICE_GUIDANCE",false,true,false,false,false,false,null);
        var input=new ChannelRankingCondition(search.intent(),search.customerType(),search.productType(),true,evaluation.policies(),facts,general);
        var first=rankingService.evaluate(input);
        assertEquals(ChannelType.CHATBOT,first.recommendations().getFirst().channel());
        assertEquals(first,rankingService.evaluate(input));
        var individual=new ConsultationContext("ACCOUNT_SPECIFIC_CHECK",true,true,true,false,false,true,null);
        var second=rankingService.evaluate(new ChannelRankingCondition(search.intent(),search.customerType(),search.productType(),true,evaluation.policies(),facts,individual));
        assertEquals(List.of(ChannelType.PHONE),second.recommendations().stream().map(ChannelRecommendation::channel).toList());
        var async=new ConsultationContext("SELF_SERVICE_GUIDANCE",false,false,true,false,true,false,null);
        var third=rankingService.evaluate(new ChannelRankingCondition(search.intent(),search.customerType(),search.productType(),true,evaluation.policies(),facts,async));
        assertEquals(ChannelType.EMAIL,third.recommendations().getFirst().channel());
        assertTrue(third.recommendations().getFirst().reasons().stream().anyMatch(s->s.contains("모의")));
        assertEquals(PolicyEvaluation.Status.NEEDS_INFORMATION,policyService.evaluate(new PolicySearchCondition(search.intent(),null,null,null)).status());
        assertTrue(policyService.findApplicablePolicies(new PolicySearchCondition("UNKNOWN",null,null,null)).isEmpty());
    }

    @Test @Transactional void repositoryFiltersActualSqlDateBoundariesAndDrafts() {
        LocalDate day=LocalDate.of(2026,10,27);
        String intent="TEST_"+UUID.randomUUID();
        em.persist(new ConsultationPolicyEntity(intent+"_BOUNDARY",intent,"TEST","APPROVED",day,day,"{}"));
        em.persist(new ConsultationPolicyEntity(intent+"_PAST",intent,"TEST","APPROVED",day.minusDays(2),day.minusDays(1),"{}"));
        em.persist(new ConsultationPolicyEntity(intent+"_FUTURE",intent,"TEST","APPROVED",day.plusDays(1),null,"{}"));
        em.persist(new ConsultationPolicyEntity(intent+"_DRAFT",intent,"TEST","DRAFT",day,null,"{}"));
        em.flush();
        assertEquals(2,repository.findPolicies(intent,day,true).size());
        assertEquals(1,repository.findPolicies(intent,day,false).size());
        assertTrue(repository.findPolicies("NO_SUCH_INTENT",day,true).isEmpty());
        // Spring 테스트 트랜잭션이 종료될 때 TEST 행은 자동 롤백된다.
    }
}
