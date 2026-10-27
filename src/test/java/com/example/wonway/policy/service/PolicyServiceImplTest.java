package com.example.wonway.policy.service;

import com.example.wonway.common.contract.*;
import com.example.wonway.policy.persistence.PolicyKnowledgeRepository;
import com.example.wonway.policy.model.PolicyEvaluation;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.stream.StreamSupport;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PolicyServiceImplTest {
    static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-27T03:00:00Z"),ZoneId.of("Asia/Seoul"));
    ObjectNode data;PolicyKnowledgeRepository repo;PolicyServiceImpl service;
    @BeforeEach void setup() throws Exception {
        try(var in=getClass().getResourceAsStream("/policy/policies.json")) {data=(ObjectNode)new ObjectMapper().readTree(in);}
        repo=mock(PolicyKnowledgeRepository.class);
        when(repo.findPolicies(anyString(),any(),anyBoolean())).thenAnswer(inv->rows("policies").stream().filter(p->p.path("intent").asText().equals(inv.getArgument(0))).map(JsonNode::toString).toList());
        when(repo.findBranches(anyString())).thenAnswer(inv->rows("branches").stream().filter(p->p.path("policyCode").asText().equals(inv.getArgument(0))).map(JsonNode::toString).toList());
        when(repo.findSourcesForBranch(anyString())).thenAnswer(inv->rows("sources").stream().map(JsonNode::toString).toList());
        service=new PolicyServiceImpl(repo,true,CLOCK);
    }
    List<JsonNode> rows(String field) {return StreamSupport.stream(data.path(field).spliterator(),false).toList();}
    ObjectNode policy() {return (ObjectNode)rows("policies").stream().filter(p->p.path("policyCode").asText().equals("INTERBANK_AUTO")).findFirst().orElseThrow();}
    PolicySearchCondition query(Map<String,Object> facts) {return new PolicySearchCondition("INTERBANK_AUTO_TRANSFER",null,null,null,facts);}
    Map<String,Object> facts() {return Map.of("transferType","INTERBANK","operation","CHANGE_DATE");}

    @Test void applicablePolicyContainsGuidanceAndSourceWithoutInventedId() {
        var result=service.evaluate(query(facts()));
        assertEquals(PolicyEvaluation.Status.MATCHED,result.status());
        assertEquals(1,result.policies().size());
        assertNull(result.policies().getFirst().policyId());
        assertFalse(result.matches().getFirst().branches().getFirst().officialSteps().isEmpty());
        assertFalse(result.matches().getFirst().branches().getFirst().sourceUrls().isEmpty());
        assertEquals("DEMO_ONLY",result.matches().getFirst().evidenceLevel());
    }
    @Test void missingFactsAreNotUnsupportedOrFalse() {
        var result=service.evaluate(query(Map.of("operation","CHANGE_DATE")));
        assertEquals(PolicyEvaluation.Status.NEEDS_INFORMATION,result.status());
        assertEquals(List.of("transferType"),result.missingFields());
        assertEquals(1,service.findApplicablePolicies(query(Map.of())).size());
        assertTrue(result.matches().getFirst().branches().getFirst().officialSteps().isEmpty());
    }
    @Test void mismatchedFactsAndUnknownIntentReturnEmpty() {
        assertTrue(service.findApplicablePolicies(query(Map.of("transferType","INTERNAL","operation","CHANGE_DATE"))).isEmpty());
        var result=service.evaluate(new PolicySearchCondition("UNKNOWN",null,null,null));
        assertEquals(PolicyEvaluation.Status.UNSUPPORTED,result.status());
        assertEquals("지금은 지원하지 않는 기능입니다",result.message());
    }
    @Test void validityDatesAreInclusiveAndOutsideDatesExcluded() {
        policy().put("effectiveFrom","2026-10-27").put("effectiveTo","2026-10-27");
        assertEquals(1,service.findApplicablePolicies(query(facts())).size());
        policy().put("effectiveFrom","2026-10-28").putNull("effectiveTo");
        assertTrue(service.findApplicablePolicies(query(facts())).isEmpty());
        policy().put("effectiveFrom","2026-10-01").put("effectiveTo","2026-10-26");
        assertTrue(service.findApplicablePolicies(query(facts())).isEmpty());
    }
    @Test void productAndCustomerMismatchAreExcluded() {
        assertTrue(service.findApplicablePolicies(new PolicySearchCondition("INTERBANK_AUTO_TRANSFER",null,"IRP",null,facts())).isEmpty());
        policy().put("customerScope","INDIVIDUAL");
        assertTrue(service.findApplicablePolicies(new PolicySearchCondition("INTERBANK_AUTO_TRANSFER","CORPORATE_EMPLOYEE",null,null,facts())).isEmpty());
    }
    @Test void identityRequirementIsNotAuthenticationCompletion() {
        var request=new PolicySearchCondition("INTERBANK_AUTO_TRANSFER",null,null,true,facts());
        assertEquals(PolicyEvaluation.Status.REVIEW_REQUIRED,service.evaluate(request).status());
        policy().put("requiresIdentityVerification",false);
        assertTrue(service.findApplicablePolicies(request).isEmpty());
        policy().put("requiresIdentityVerification",true);
        assertEquals(PolicyEvaluation.Status.MATCHED,service.evaluate(request).status());
    }
    @Test void productionExcludesDraftAndRequiresApprovalMetadata() {
        var prod=new PolicyServiceImpl(repo,false,CLOCK);
        assertTrue(prod.findApplicablePolicies(query(facts())).isEmpty());
        policy().put("approvalStatus","APPROVED").put("verifiedBy","TEST_REVIEWER").put("effectiveFrom","2026-01-01");
        assertEquals(1,prod.findApplicablePolicies(query(facts())).size());
        policy().putNull("verifiedBy");
        assertTrue(prod.findApplicablePolicies(query(facts())).isEmpty());
    }
    @Test void twoMinorPoliciesCanMatchTogether() {
        var result=service.evaluate(new PolicySearchCondition("MINOR_ACCOUNT_PREPARATION",null,null,null,Map.of("childAge",16,"visitorType","SELF")));
        assertEquals(2,result.policies().size());
        assertTrue(result.matches().stream().flatMap(p->p.branches().stream()).allMatch(b->b.branchCode().startsWith("MINOR_SELF")));
    }
    @Test void invalidTypeAndRepositoryFailureAreNotEmptySuccess() {
        assertThrows(IllegalArgumentException.class,()->service.evaluate(new PolicySearchCondition("MINOR_ACCOUNT_PREPARATION",null,null,null,Map.of("childAge","16","visitorType","SELF"))));
        when(repo.findPolicies(anyString(),any(),anyBoolean())).thenThrow(new IllegalStateException("DB unavailable"));
        assertThrows(IllegalStateException.class,()->service.evaluate(query(facts())));
    }

    @Test void nextRequestDoesNotInheritPreviouslyKnownFacts() {
        assertEquals(PolicyEvaluation.Status.MATCHED, service.evaluate(query(facts())).status());
        var next = service.evaluate(query(Map.of("operation", "CHANGE_DATE")));
        assertEquals(PolicyEvaluation.Status.NEEDS_INFORMATION, next.status());
        assertEquals(List.of("transferType"), next.missingFields());
        assertTrue(next.matches().stream().flatMap(m -> m.branches().stream())
                .allMatch(b -> b.officialSteps().isEmpty()));
    }
}
