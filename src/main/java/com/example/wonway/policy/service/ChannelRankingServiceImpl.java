package com.example.wonway.policy.service;

import com.example.wonway.common.contract.*;
import com.example.wonway.policy.model.*;
import com.example.wonway.policy.ranking.ChannelCapabilityProvider;
import com.example.wonway.policy.ranking.ChannelCapabilityProvider.*;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ChannelRankingServiceImpl implements ChannelRankingService {
    public static final String SCORING_VERSION="context-v2-guidance-comparison";
    private final PolicyServiceImpl policies;
    private final ChannelCapabilityProvider capabilities;
    public ChannelRankingServiceImpl(PolicyServiceImpl policies,ChannelCapabilityProvider capabilities) {
        this.policies=policies;this.capabilities=capabilities;
    }
    @Override public List<ChannelRecommendation> rankChannels(ChannelRankingCondition request) {return evaluate(request).recommendations();}

    public ChannelEvaluation evaluate(ChannelRankingCondition request) {
        Objects.requireNonNull(request,"채널 평가 입력이 필요합니다.");
        if(request.policies().isEmpty())return empty("UNSUPPORTED");
        // 모델이 전달한 verifiedContent·제목·기간은 재사용하지 않는다. 코드로 다시 검증한다.
        var current=policies.evaluate(new PolicySearchCondition(request.intent(),request.customerType(),request.productType(),null,request.facts()));
        if(current.status()!=PolicyEvaluation.Status.MATCHED)return empty(current.status().name());
        Set<String> requestedCodes=new TreeSet<>();
        for(var policy:request.policies()) {
            if(policy.policyCode()==null)throw new IllegalArgumentException("policyCode 누락");
            requestedCodes.add(policy.policyCode());
        }
        List<PolicyEvaluation.Match> selected=current.matches().stream().filter(m->requestedCodes.contains(m.policy().policyCode())).toList();
        if(selected.size()!=requestedCodes.size())return empty("POLICY_REFERENCE_MISMATCH");
        List<PolicyEvaluation.Branch> applicable=selected.stream().flatMap(m->m.branches().stream()).filter(b->b.applicability().equals("APPLICABLE")).toList();
        if(applicable.isEmpty())return empty("NEEDS_INFORMATION");
        // 현재 요청의 상황만 평가한다. 이전 추천이나 고객 상황은 저장하지 않는다.
        ConsultationContext c=request.context();
        if(c.requestGoal()==null)return empty("NEEDS_CONTEXT");
        if(!Set.of("GENERAL_GUIDANCE","SELF_SERVICE_GUIDANCE","ACCOUNT_SPECIFIC_CHECK","EXECUTION_REQUEST").contains(c.requestGoal()))
            throw new IllegalArgumentException("지원하지 않는 requestGoal");
        if(c.preferredChannel()!=null && !Set.of("CHATBOT","PHONE","EMAIL").contains(c.preferredChannel()))
            throw new IllegalArgumentException("preferredChannel은 CHATBOT/PHONE/EMAIL만 지원합니다.");
        if(c.requestGoal().equals("EXECUTION_REQUEST"))return empty("EXECUTION_NOT_IMPLEMENTED");
        Set<String> organizations=new TreeSet<>();applicable.forEach(b->organizations.add(b.contactOrganization()));
        if(organizations.size()!=1)return empty("CONTACT_SCOPE_REVIEW_REQUIRED");
        boolean needsIndividual=Boolean.TRUE.equals(c.needsAccountSpecificCheck()) || c.requestGoal().equals("ACCOUNT_SPECIFIC_CHECK");
        boolean urgent=Boolean.TRUE.equals(c.urgent()) || Boolean.TRUE.equals(request.facts().get("unknownPayment")) || "REPORT_LOSS".equals(request.facts().get("requestKind"));
        List<String> codes=List.copyOf(requestedCodes);
        List<ChannelEvaluation.Decision> excluded=new ArrayList<>();List<ChannelRecommendation> ranked=new ArrayList<>();
        Set<ChannelType> seen=new HashSet<>();
        for(Capability cap:capabilities.snapshot(organizations.iterator().next())) {
            if(!Set.of(ChannelType.CHATBOT,ChannelType.PHONE,ChannelType.EMAIL).contains(cap.channel()) || !seen.add(cap.channel()))
                throw new IllegalStateException("채널 운영정보 중복 또는 지원하지 않는 종류");
            List<String> reasons=new ArrayList<>();String eligibility="ELIGIBLE";
            String goal=needsIndividual?"ACCOUNT_SPECIFIC_CHECK":c.requestGoal();
            if(!cap.supportVerified() || cap.availability()==Availability.UNKNOWN) {eligibility="UNKNOWN";reasons.add("지원 범위 또는 현재 운영 상태가 확인되지 않음");}
            else if(cap.availability()==Availability.UNAVAILABLE) {eligibility="INELIGIBLE";reasons.add("현재 이용 불가");}
            else if(!cap.supportedGoals().contains(goal)) {eligibility="INELIGIBLE";reasons.add("현재 상담 목적을 지원하지 않음");}
            else if(needsIndividual && cap.identityNeededForIndividualCheck() && !Boolean.TRUE.equals(request.identityVerificationAvailable())) {
                eligibility=request.identityVerificationAvailable()==null?"UNKNOWN":"INELIGIBLE";reasons.add("개별 확인에 필요한 인증 수단 이용 가능 여부 미충족·미확인");
            }
            if(cap.channel()==ChannelType.CHATBOT && needsIndividual) {
                eligibility="INELIGIBLE";reasons.add("개별 계좌 확인은 챗봇에서 지원하지 않음");
            }
            if(!eligibility.equals("ELIGIBLE")) {
                excluded.add(new ChannelEvaluation.Decision(cap.channel(),eligibility,null,null,List.copyOf(reasons),codes));continue;
            }
            int completion=40;
            int timing=cap.channel()==ChannelType.CHATBOT?20:cap.channel()==ChannelType.PHONE?(urgent?20:14):6;
            int access=cap.channel()==ChannelType.CHATBOT && c.requestGoal().equals("SELF_SERVICE_GUIDANCE") && !Boolean.TRUE.equals(c.selfServiceAvailable())?0:15;
            int interaction=cap.channel()==ChannelType.EMAIL?15:cap.channel()==ChannelType.PHONE?
                (Boolean.TRUE.equals(c.realTimeConversationNeeded())?15:
                    Boolean.FALSE.equals(c.realTimeConversationNeeded()) && Boolean.TRUE.equals(c.acceptsAsyncReply())?5:8):12;
            int preference=cap.channel().name().equals(c.preferredChannel())?10:0;
            // Guidance suitability affects order, not whether a supported channel appears at all.
            if(cap.channel()==ChannelType.EMAIL) {
                if(urgent || Boolean.TRUE.equals(c.realTimeConversationNeeded()) || Boolean.FALSE.equals(c.acceptsAsyncReply())) {
                    timing=0;interaction=0;
                    reasons.add("고객안내: 이메일은 답변을 기다리는 방식이라 현재 상황에서는 후순위로 안내해요.");
                } else if(c.urgent()==null || c.acceptsAsyncReply()==null || c.realTimeConversationNeeded()==null) {
                    interaction=0;
                    reasons.add("고객안내: 이메일은 나중에 답변받는 방식이며, 관련 선호가 확인되지 않아 보수적으로 평가했어요.");
                }
            }
            if(cap.channel()==ChannelType.CHATBOT) {
                if(urgent)timing=10;
                if(c.requestGoal().equals("SELF_SERVICE_GUIDANCE") && Boolean.TRUE.equals(c.selfServiceAttemptFailed())) {
                    timing=0;interaction=0;access=0;
                    reasons.add("고객안내: 이미 직접 처리를 시도해 실패하셨으므로 같은 메뉴 안내는 후순위로 두었어요.");
                }
                if(c.preferredChannel()!=null && !c.preferredChannel().equals("CHATBOT")) {
                    preference=-10;
                    reasons.add("고객안내: 사람 상담을 원하셔서 챗봇 안내는 후순위로 평가했어요.");
                }
            }
            reasons.add("상담 목적 지원 +"+completion+" (실제 금융업무 실행 완료 의미 아님)");
            reasons.add("응답 방식 적합성 +"+timing+" (실제 대기시간은 평가하지 않음)");
            reasons.add(access==0?"셀프서비스 이용 가능 미확인·불가: 접근성 점수 미부여":"이용 경로·인증 수단 조건 충족 +"+access+" (실제 본인인증은 별도)");
            reasons.add("실시간/비동기 문답 적합성 +"+interaction);
            reasons.add("명시 선호 +"+preference);
            reasons.add("적용 정책: "+String.join(",",codes));
            reasons.add("운영 근거: "+cap.basis());
            if(cap.demo())reasons.add("개발용 모의 운영정보이며 실제 은행 가용성 확인 결과가 아님");
            if(selected.stream().anyMatch(m->m.evidenceLevel().equals("DEMO_ONLY")))reasons.add("개발용 DRAFT 정책을 사용함");
            String url=cap.officialUrl();
            if(url==null)url=applicable.stream().flatMap(b->b.sourceUrls().stream()).sorted().findFirst().orElse(null);
            ranked.add(new ChannelRecommendation(cap.channel(),completion+timing+access+interaction+preference,true,List.copyOf(reasons),url));
        }
        ranked.sort(Comparator.comparingInt(ChannelRecommendation::score).reversed().thenComparing(r->r.channel().name()));
        List<ChannelEvaluation.Decision> decisions=new ArrayList<>();int rank=0,previous=-1;
        for(int i=0;i<ranked.size();i++) {
            var r=ranked.get(i);if(r.score()!=previous)rank=i+1;previous=r.score();
            decisions.add(new ChannelEvaluation.Decision(r.channel(),"ELIGIBLE",r.score(),rank,r.reasons(),codes));
        }
        excluded.sort(Comparator.comparing(d->d.channel().name()));decisions.addAll(excluded);
        return new ChannelEvaluation(ranked.isEmpty()?"NO_ELIGIBLE_CHANNEL":"RANKED",List.copyOf(ranked),List.copyOf(decisions),SCORING_VERSION);
    }
    private ChannelEvaluation empty(String status) {return new ChannelEvaluation(status,List.of(),List.of(),SCORING_VERSION);}
}
