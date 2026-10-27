# B 정책 검증·상담 채널 평가 연결

## 구현 파일

- policy/service/PolicyServiceImpl: JPA 조회 후 기간·고객·상품·상황 조건 검증
- policy/service/PolicyConditionEvaluator: EQ/IN/BETWEEN의 일치·불일치·미확인 비교
- policy/service/ChannelRankingServiceImpl: 정책 재조회 → 상담 가능 여부 → 점수·순위·근거
- policy/ranking/ChannelCapabilityProvider: 서버가 제공하는 지원 범위·운영 상태 계약
- policy/ranking/DefaultChannelCapabilityProvider: 기본 미확인 상태와 명시적 데모 운영정보
- policy/model/PolicyEvaluation, ChannelEvaluation: 상세 상태·질문 필요 항목·제외 사유

모델 호출, ChatMemory, Controller, 실제 거래 실행 및 상담원 연결은 구현하지 않았습니다.
기존 kb_ 테이블 데이터는 그대로 사용합니다. DB 업데이트·재적재 없이 조회합니다.

## 승인받아 변경한 공용 계약

- ChannelType: 기존 값 유지 + EMAIL 추가. 새 평가기는 CHATBOT/PHONE/EMAIL만 반환.
- PolicySearchCondition: facts 추가, 기존 4인자 생성자 유지.
- ChannelRankingCondition: facts/context 추가, 기존 5인자 생성자 유지.
- ConsultationContext 신규: 상담 목적, 개별 확인 필요, 셀프서비스 이용 가능·실패, 긴급성, 비동기 수용, 실시간 문답 필요, 명시 선호.
- PolicyResult 및 ChannelRecommendation 필드 유지.

현재 DB의 식별자는 policyCode 문자열입니다. PolicyResult.policyId에는 가상의 숫자를 넣지 않고 null을 반환합니다. A는 policyCode로 후속 평가를 요청해야 합니다.

## A 도구에서 호출하는 예

```java
var facts = Map.<String, Object>of(
    "transferType", "INTERBANK",
    "operation", "CHANGE_DATE"
);
var condition = new PolicySearchCondition(
    "INTERBANK_AUTO_TRANSFER", "INDIVIDUAL", "INTERBANK_TRANSFER", null, facts
);
var found = policyService.evaluate(condition);
// MATCHED / NEEDS_INFORMATION / REVIEW_REQUIRED / UNSUPPORTED 구분

var context = new ConsultationContext(
    "SELF_SERVICE_GUIDANCE", false, true, false,
    false, false, false, null
);
var ranked = channelRankingService.evaluate(new ChannelRankingCondition(
    condition.intent(), condition.customerType(), condition.productType(),
    true, found.policies(), facts, context
));
// ranked.recommendations(): 가능한 채널만. decisions(): 제외/미확인 사유 포함.
```

이 예시의 false/true를 모든 고객에게 기본값으로 넣지 않습니다. 현재 사용자 메시지에서 파악되지 않은 사실은 null/누락입니다. 상담 목적은 GENERAL_GUIDANCE, SELF_SERVICE_GUIDANCE, ACCOUNT_SPECIFIC_CHECK, EXECUTION_REQUEST를 사용합니다. EXECUTION_REQUEST는 MVP에서 실제 처리가 불가능하므로 EXECUTION_NOT_IMPLEMENTED를 반환합니다.

의도·상품·상황 값은 저장된 JSON 코드에 맞춰 추출합니다. 일반 생활비 날짜 변경 문의만으로 타행 자동이체라고 가정하면 안 됩니다. 타행 여부 등 필요한 조건만 확인합니다.

기존 findApplicablePolicies/rankChannels 메서드도 유지합니다. 정책 조회 결과의 verifiedContent는 상태·근거·분기 정보를 담은 JSON 문자열입니다. 정보부족은 빈 목록이 아니라 NEEDS_INFORMATION 분기를 가진 결과입니다. A가 상태를 쉽게 사용하려면 evaluate 메서드를 도구 내부에서 호출하세요. 도구 이름을 바꿀 필요는 없습니다.

## 정책 검증 규칙

- 적용 시작/종료일 양끝 포함. 범위 밖·고객/상품 범위 불일치·분기 조건 불일치는 제외.
- 알려지지 않은 사실을 false로 바꾸지 않음. 답변을 확정할 수 없는 분기의 안내 절차는 비워서 반환.
- missingFields 전체를 고객에게 그대로 나열하지 말고, 관련 분기의 답을 바꾸는 조건만 A가 묶어서 확인.
- requiresIdentityVerification은 업무의 본인확인 필요 여부 필터. 현재 FAQ에 구조화된 해당 메타데이터가 없으면 REVIEW_REQUIRED이며, 이미 인증됐다고 해석하지 않음.
- 미지원은 빈 목록 및 UNSUPPORTED. 고정 안내: 지금은 지원하지 않는 기능입니다.
- DB 장애·JSON 오류는 예외로 전파하며 미지원으로 위장하지 않음.
- 기본 운영 조회는 APPROVED + 검토자 + 시행일이 있는 정책만 통과. 출처 충돌·범위 불명확은 안내 확정 보류.
- 현재 실제 정책은 모두 DRAFT. 개발 모드에서만 DEMO_ONLY 표시와 함께 안내.

## 채널 평가 규칙

고객이 보내는 policies의 본문·제목·숫자 ID는 신뢰하지 않습니다. policyCode로 서버 정책을 다시 조회하고 고객 상황 조건도 재검증합니다. 앱/웹/영업점의 거래 처리 기능과 상담 채널은 별개입니다. 본 모듈은 거래를 실행하지 않습니다.

먼저 지원 범위·현재 가용성·개별 인증 수단 조건을 확인합니다. 이미 실패한 셀프서비스 안내를 반복하는 챗, 개별 거래 확인이 필요한 챗, 긴급/실시간 요구와 맞지 않는 이메일은 제외합니다. 운영정보 미확인은 UNKNOWN이며 점수·순위를 부여하지 않습니다.

현재 평가식 버전: context-v1-40-20-15-15-10. 은행 공식 배점이 아닌 프로젝트 공통 평가식입니다.

| 항목 | 점수 규칙 |
| --- | --- |
| 현재 상담 목적 지원 | 처리 가능 검증 통과 시 40 |
| 응답 방식 | 챗 20, 전화 일반 14·긴급 20, 비동기 이메일 6 |
| 이용 조건 | 15. 셀프서비스 안내인데 앱 이용 가능 미확인/불가이면 챗 0 |
| 문답 적합성 | 챗 12, 이메일(수용 확인) 15, 전화 실시간 필요 15·실시간 불필요+비동기 수용 5·그 외 8 |
| 명시적 선호 | 일치 10, 없거나 불일치 0 |

업무별 채널 고정 점수표가 아닙니다. 같은 업무도 상황에 따라 제외 대상과 항목 점수가 달라집니다. 실제 대기시간을 0분으로 추정하지 않습니다. 동일한 정책·고객 상황·운영 스냅샷·평가식이면 같은 결과입니다. 동일 점수는 공동 순위이며 리스트의 채널명 정렬은 표시 안정성만 위한 것입니다.

## 실제 운영정보와 데모의 구분

기본 채널 제공자는 전화·이메일의 현재 이용 가능성과 업무별 처리 범위를 확인하지 못하므로 UNKNOWN을 반환합니다. 공식 페이지가 있다는 이유만으로 지금 연결 가능·처리 가능하다고 확정하지 않습니다. 기관 힌트가 특정 영업점인 경우 대표번호로 대체하지 않습니다.

IntelliJ 실행 설정의 Active profiles에 policy-demo를 지정하면:
- policy.demo-mode=true: 개발용 DRAFT FAQ 조회
- policy.channel-demo-mode=true: 모의 전화/이메일 상담 운영정보 사용

모의 근거는 모든 해당 추천 이유에 표시됩니다. 실제 은행 상담 시스템 연동 결과가 아닙니다. 운영에서는 검증된 ChannelCapabilityProvider 구현으로 교체해야 합니다. 공용 application.yaml과 build.gradle은 변경하지 않았습니다.

## 테스트

```sh
./gradlew test --tests 'com.example.wonway.policy.service.*' --tests 'com.example.wonway.policy.persistence.PolicyJsonDocumentTest'
POLICY_MYSQL_VERIFY=true ./gradlew test --tests 'com.example.wonway.policy.service.*' --tests 'com.example.wonway.policy.persistence.PolicyJsonDocumentTest' --rerun-tasks
```

2026-10-27: 실제 backend 컴파일 및 32개 테스트 실행 통과. MySQL Repository 날짜 경계·DRAFT 제외, Spring 서비스 연결, 조건 미확인/불일치, 정책 0건, 정책 본문 변조 무시, 채널 제외, 동일 입력 재현성, 같은 업무의 상황별 추천 변화 검증. 기간 검증용 TEST 행은 Spring 테스트 트랜잭션으로 롤백됩니다.

A의 실제 LLM 도구 호출·프론트 화면·실제 은행 상담 운영 시스템은 검증 범위 밖입니다.
