package com.example.wonway.policy.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import static com.example.wonway.policy.persistence.PolicyJsonDocument.*;

/** 서버 관리용 일회성 적재. A의 LLM 도구로 노출하지 않는다. */
@Service
public class PolicyImportService {
    @PersistenceContext private EntityManager em;
    public PolicyImportService() {}
    // 독립 JPA 검증용. Spring에서는 기본 생성자 + @PersistenceContext 사용.
    public PolicyImportService(EntityManager em) { this.em=em; }

    public record ImportResult(String status,int sourceCount,int policyCount,int branchCount,int ruleCount,String checksum) {}

    @Transactional
    public ImportResult importJson(byte[] bytes) {
        PolicyJsonDocument doc=parse(bytes); // 저장 전에 입력 구조·참조 전체 검증
        JsonNode root=doc.root();
        PolicyDatasetEntity existing=em.find(PolicyDatasetEntity.class,"CURRENT");
        if (existing!=null) {
            if (existing.getChecksum().equals(doc.checksum())) return result("ALREADY_IMPORTED",doc);
            throw new IllegalStateException("다른 정책 데이터가 이미 저장돼 있습니다. 자동 덮어쓰기는 하지 않습니다. 별도 데이터 갱신 절차가 필요합니다.");
        }
        // 다른 초기화 도구가 일부만 저장한 DB를 성공으로 오인하지 않는다.
        for (String entity: new String[]{"V4PolicySourceEntity","V4ConsultationPolicyEntity","V4PolicyBranchEntity","V4BranchSourceEntity","V4PolicyProjectRuleEntity"}) {
            // entity는 위 고정 목록에서만 선택하며 LLM 문자열을 받지 않는다.
            if (em.createQuery("select count(e) from "+entity+" e",Long.class).getSingleResult()!=0)
                throw new IllegalStateException("일부 정책 데이터가 이미 존재합니다. 비어 있는 v4 저장 영역에서 시작하세요.");
        }
        // CURRENT PK가 동시 초기화 충돌도 감지한다. 실패한 트랜잭션은 전체 롤백.
        em.persist(new PolicyDatasetEntity("CURRENT",4,doc.checksum(),doc.metadata().toString()));
        em.flush();
        for (JsonNode s:root.path("sources"))
            em.persist(new PolicySourceEntity(required(s,"sourceId"),required(s,"title"),nullableText(s,"sourceUrl"),required(s,"evidenceStatus"),s.toString()));
        for (JsonNode p:root.path("policies"))
            em.persist(new ConsultationPolicyEntity(required(p,"policyCode"),required(p,"intent"),required(p,"title"),required(p,"approvalStatus"),date(p,"effectiveFrom"),date(p,"effectiveTo"),p.toString()));
        em.flush();
        for (JsonNode b:root.path("branches"))
            em.persist(new PolicyBranchEntity(required(b,"branchCode"),required(b,"policyCode"),b.path("conditions").toString(),b.path("consultationAssessment").toString(),b.toString()));
        em.flush();
        for (JsonNode b:root.path("branches"))
            for (JsonNode ref:b.path("sourceIds")) em.persist(new BranchSourceEntity(required(b,"branchCode"),ref.asText()));
        for (JsonNode r:root.path("projectRules"))
            em.persist(new PolicyProjectRuleEntity(required(r,"code"),r.toString()));
        em.flush();
        return result("IMPORTED",doc);
    }
    private ImportResult result(String status,PolicyJsonDocument doc) {
        JsonNode n=doc.root();
        return new ImportResult(status,n.path("sources").size(),n.path("policies").size(),n.path("branches").size(),n.path("projectRules").size(),doc.checksum());
    }
}
