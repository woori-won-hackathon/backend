package com.example.wonway.policy.service;

import com.example.wonway.common.contract.PolicyResult;
import com.example.wonway.common.contract.PolicySearchCondition;
import com.example.wonway.policy.model.PolicyEvaluation;
import java.util.List;

/** 담당 B: 등록 정책 조회 및 고객 조건 검증. */
public interface PolicyService {
    // 입력은 현재 메시지에서 추출한 값만 사용한다. 이전 요청의 사실을 보충하지 않는다.
    List<PolicyResult> findApplicablePolicies(PolicySearchCondition condition);
    /** A가 정보부족/미지원/검토필요 상태까지 구분할 때 사용한다. */
    PolicyEvaluation evaluate(PolicySearchCondition condition);
}
