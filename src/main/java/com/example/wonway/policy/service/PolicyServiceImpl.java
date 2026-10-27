package com.example.wonway.policy.service;

import com.example.wonway.common.contract.*;
import com.example.wonway.policy.model.PolicyEvaluation;
import com.example.wonway.policy.persistence.PolicyKnowledgeRepository;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static com.example.wonway.policy.model.PolicyEvaluation.*;

@Service
@Transactional(readOnly=true)
public class PolicyServiceImpl implements PolicyService {
    private final PolicyKnowledgeRepository repository;
    private final boolean demoMode;
    private final Clock clock;
    private final ObjectMapper mapper=new ObjectMapper();
    private final PolicyConditionEvaluator evaluator=new PolicyConditionEvaluator();

    @Autowired
    public PolicyServiceImpl(PolicyKnowledgeRepository repository,@Value("${policy.demo-mode:false}") boolean demoMode) {
        this(repository,demoMode,Clock.system(ZoneId.of("Asia/Seoul")));
    }
    public PolicyServiceImpl(PolicyKnowledgeRepository repository,boolean demoMode,Clock clock) {
        this.repository=repository;this.demoMode=demoMode;this.clock=clock;
    }
    @Override public List<PolicyResult> findApplicablePolicies(PolicySearchCondition condition) {
        // 기존 계약의 verifiedContent에도 정보부족 상태를 담아 빈 목록(미지원)과 구분한다.
        return evaluate(condition).policies();
    }
    public PolicyEvaluation evaluate(PolicySearchCondition request) {
        Objects.requireNonNull(request,"정책 조회 입력이 필요합니다.");
        if(request.intent()==null || request.intent().isBlank())throw new IllegalArgumentException("intent가 필요합니다.");
        // 이 호출의 입력만 복사한다. 누락된 값을 이전 요청에서 가져오지 않는다.
        Map<String,Object> facts=new LinkedHashMap<>(request.facts());
        if(request.customerType()!=null) {
            Object previous=facts.putIfAbsent("customerType",request.customerType());
            if(previous!=null && !previous.equals(request.customerType()))throw new IllegalArgumentException("customerType과 facts 충돌");
        }
        List<Match> matches=new ArrayList<>();Set<String> missing=new TreeSet<>();
        boolean applicable=false, review=false;
        LocalDate today=LocalDate.now(clock);
        for(String text:repository.findPolicies(request.intent(),today,demoMode)) {
            JsonNode p=read(text);
            // 저장소 외에도 서비스 경계에서 기간·승인·조회 범위를 재확인한다.
            if(!request.intent().equals(p.path("intent").asText()) || !withinDates(p,today))continue;
            if(!demoMode && (!"APPROVED".equals(p.path("approvalStatus").asText()) || blank(p,"verifiedBy") || blank(p,"effectiveFrom")))continue;
            if(!scopeMatches(request.customerType(),p.path("customerScope").asText(),true) || !scopeMatches(request.productType(),p.path("productScope").asText(),false))continue;
            boolean identityUnknown=request.requiresIdentityVerification()!=null && !p.path("requiresIdentityVerification").isBoolean();
            if(!identityUnknown && request.requiresIdentityVerification()!=null && request.requiresIdentityVerification()!=p.path("requiresIdentityVerification").asBoolean())continue;
            String code=p.path("policyCode").asText(); List<Branch> branches=new ArrayList<>();
            for(String branchText:repository.findBranches(code)) {
                JsonNode b=read(branchText);
                if(!code.equals(b.path("policyCode").asText()))throw new IllegalStateException("정책-분기 연결 오류");
                var result=evaluator.evaluate(b.path("conditions"),facts);
                if(result.state()==PolicyConditionEvaluator.State.MISMATCH)continue;
                List<String> urls=repository.findSourcesForBranch(b.path("branchCode").asText()).stream()
                    .map(this::read).map(s->s.path("sourceUrl").asText("")).filter(u->u.startsWith("https://")).distinct().sorted().toList();
                String evidence=b.path("evidenceStatus").asText();
                boolean blocked=identityUnknown || evidence.equals("SOURCE_CONFLICT") || evidence.equals("SOURCE_SCOPE_UNRESOLVED") || (!demoMode && !evidence.equals("WEB_CHECKED"));
                String state=result.state()==PolicyConditionEvaluator.State.UNKNOWN?"NEEDS_INFORMATION":blocked?"REVIEW_REQUIRED":"APPLICABLE";
                if(state.equals("APPLICABLE"))applicable=true;
                if(blocked)review=true;
                missing.addAll(result.missingFields());
                List<String> remaining=new ArrayList<>(strings(b.path("remainingChecks")));
                if(identityUnknown)remaining.add("정책의 본인확인 필요 여부 메타데이터 검토 필요. 고객의 인증 완료 여부와 다름.");
                branches.add(new Branch(b.path("branchCode").asText(),state,result.missingFields(),
                    state.equals("APPLICABLE")?strings(b.path("officialSteps")):List.of(),strings(b.path("restrictions")),List.copyOf(remaining),urls,evidence,
                    b.path("consultationAssessment").path("contactOrganizationHint").asText("WOORI_BANK")));
            }
            if(branches.isEmpty())continue;
            String level="APPROVED".equals(p.path("approvalStatus").asText())?"APPROVED_POLICY":"DEMO_ONLY";
            String content=write(Map.of("evidenceLevel",level,"branches",branches,"identityRequirementUnverified",identityUnknown));
            // DB의 PK는 문자열 policyCode. 존재하지 않는 Long 식별자를 만들지 않는다.
            PolicyResult dto=new PolicyResult(null,code,p.path("title").asText(),content,date(p,"effectiveFrom"),date(p,"effectiveTo"));
            matches.add(new Match(dto,List.copyOf(branches),level));
        }
        Status status=matches.isEmpty()?Status.UNSUPPORTED:applicable?Status.MATCHED:!missing.isEmpty()?Status.NEEDS_INFORMATION:review?Status.REVIEW_REQUIRED:Status.UNSUPPORTED;
        return new PolicyEvaluation(status,List.copyOf(matches),List.copyOf(missing),status==Status.UNSUPPORTED?"지금은 지원하지 않는 기능입니다":null);
    }
    private boolean scopeMatches(String requested,String scope,boolean customer) {
        if(requested==null || requested.isBlank())return true;
        if(Set.of("CONDITION_DEPENDENT","CHANNEL_DEPENDENT","ISSUER_DEPENDENT","FAQ_CONDITION_DEPENDENT","FAQ_SPECIFIC").contains(scope))return true;
        if(customer && scope.equals("MINOR") && requested.equals("INDIVIDUAL"))return true;
        return requested.equals(scope);
    }
    private boolean withinDates(JsonNode p,LocalDate now) {
        LocalDate from=date(p,"effectiveFrom"),to=date(p,"effectiveTo");
        if(from!=null && to!=null && to.isBefore(from))throw new IllegalStateException("정책 적용 기간 오류");
        return (from==null || !now.isBefore(from)) && (to==null || !now.isAfter(to));
    }
    private boolean blank(JsonNode n,String k) {return n.path(k).isNull() || n.path(k).asText("").isBlank();}
    private LocalDate date(JsonNode n,String k) {return blank(n,k)?null:LocalDate.parse(n.path(k).asText());}
    private List<String> strings(JsonNode array) {
        if(!array.isArray())return List.of();List<String> out=new ArrayList<>();array.forEach(n->out.add(n.asText()));return List.copyOf(out);
    }
    private JsonNode read(String json) {try{return mapper.readTree(json);}catch(Exception e){throw new IllegalStateException("DB 정책 JSON 오류",e);}}
    private String write(Object value) {try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("정책 결과 변환 실패",e);}}
}
