package com.example.wonway.policy.persistence;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 조회 도구 내부에서 사용할 고정 JPQL. 엔티티 대신 저장된 JSON을 반환한다. */
@Repository
public class PolicyKnowledgeRepository {
    @PersistenceContext private EntityManager em;
    public PolicyKnowledgeRepository() {}
    public PolicyKnowledgeRepository(EntityManager em) { this.em=em; }

    public List<String> findPolicies(String intent,LocalDate evaluationDate,boolean allowDraft) {
        // evaluationDate/allowDraft는 LLM이 아니라 서버 Clock·프로필로 정한다.
        return em.createQuery("""
            select p.payloadJson from V4ConsultationPolicyEntity p
            where p.intent = :intent
              and (p.effectiveFrom is null or p.effectiveFrom <= :today)
              and (p.effectiveTo is null or p.effectiveTo >= :today)
              and (:allowDraft = true or (p.approvalStatus = 'APPROVED' and p.effectiveFrom is not null))
            order by p.policyCode
            """,String.class).setParameter("intent",intent).setParameter("today",evaluationDate)
                .setParameter("allowDraft",allowDraft).getResultList();
    }
    public List<String> findBranches(String policyCode) {
        return em.createQuery("select b.payloadJson from V4PolicyBranchEntity b where b.policyCode=:code order by b.branchCode",String.class)
                .setParameter("code",policyCode).getResultList();
    }
    public List<String> findSourcesForBranch(String branchCode) {
        return em.createQuery("""
            select s.payloadJson from V4PolicySourceEntity s, V4BranchSourceEntity link
            where link.branchCode=:code and link.sourceId=s.sourceId order by s.sourceId
            """,String.class).setParameter("code",branchCode).getResultList();
    }
}
