# A 담당자: B 도구 연결

## 1. 주입받을 서비스와 공개 도구

| LLM에 보이는 도구 이름 | 내부 호출 | 결과 |
| --- | --- | --- |
| findApplicablePolicies | PolicyService.evaluate(PolicySearchCondition) | PolicyEvaluation |
| rankConsultationChannels | PolicyService.evaluate로 재조회한 뒤 ChannelRankingService.evaluate(ChannelRankingCondition) | ChannelEvaluation |

도구 이름과 Java 서비스 메서드 이름은 같을 필요가 없습니다. 기존 `findApplicablePolicies()`/`rankChannels()`도 유지하지만 목록만 반환하므로, 상태를 읽기 쉬운 `evaluate()` 연결을 권장합니다.

참고 래퍼: `ConsultationTools.java.example`. 현재 프로젝트의 Spring AI 2.0.1 로컬 소스에서 `@Tool`, `@ToolParam(required=false)`, `ChatClient.prompt().tools(Object...)` API를 확인한 예시입니다. A의 agent 패키지에는 아직 복사하지 않았습니다.

도구 등록 예:

```java
// ChatMemory/default memory advisor가 없는 전용 ChatClient를 사용한다.
// AgentService.chat()의 각 요청마다 생성한다.
var tools = new ConsultationTools(policyService, channelRankingService);
var modelResponse = chatClient.prompt()
    .system(systemPrompt)
    .user(message)
    .tools(tools)
    .call();
```

도구 결과를 단순히 다시 프롬프트 문자열에 수동 삽입하는 방식보다 Spring AI의 도구 실행 흐름으로 전달합니다. 실제 ToolCallback 등록·모델 응답·도구 반복 호출은 A 환경에서 테스트하세요. 예시는 타임아웃·파싱 오류 전체 구현이 아닙니다. 현재 요청 내부의 도구 호출과 그 결과는 모델에 전달하지만, 이전 사용자 요청이나 응답은 전달하지 않습니다.

## 2. 정책 조회 입력

```json
{
  "intent": "INTERBANK_AUTO_TRANSFER",
  "customerType": "INDIVIDUAL",
  "productType": "INTERBANK_TRANSFER",
  "requiresIdentityVerification": null,
  "facts": {
    "transferType": "INTERBANK",
    "operation": "CHANGE_DATE"
  }
}
```

이는 타행 자동이체와 날짜 변경 의사가 확인된 경우의 입력입니다. "엄마한테 매달 보내는 날짜를 바꿀래"라는 말만으로 타행 여부·자동이체 등록 여부를 추정해 넣으면 안 됩니다.

- 의도·상품·상황 코드는 `tool-input-catalog.json` 참고. 기존 단일 자동이체 intent로 모든 상품을 섞지 않습니다.
- `customerType`, `productType`가 불명확하면 null. productType은 DB productScope에 맞는 코드이며 임의 문자열을 만들지 않습니다.
- `facts`는 Boolean/정수/문자열/null의 실제 타입을 사용. `16`과 `"16"`, false와 `"false"`는 다릅니다.
- `requiresIdentityVerification`은 정책의 본인확인 필요 여부 필터입니다. 고객의 인증 가능·완료 여부가 아닙니다. 필요가 없으면 null을 사용합니다. 현재 정책에 해당 메타데이터가 없는데 true/false 필터를 주면 검토 필요가 될 수 있습니다.
- 현재 message에서만 고객 정보를 추출합니다. 같은 sessionId라도 이전 요청의 facts/context/추천을 합치지 않습니다. 문의자와 계좌 주인·실제 방문자를 구분합니다.

## 3. 정책 조회 결과 처리

| status | A 처리 |
| --- | --- |
| MATCHED | APPLICABLE 분기의 근거로 답변/채널 평가 진행. 함께 반환된 다른 미확인 분기를 확정 적용하지 않음 |
| NEEDS_INFORMATION | missingFields 중 답을 바꾸는 조건을 안내하고, 업무와 조건을 함께 포함하여 다시 입력하도록 요청. 다음 요청도 독립 평가 |
| REVIEW_REQUIRED | 자료 충돌·검증 부족 등으로 적용 확정 보류. 빈 단계 목록을 모델 지식으로 채우지 않음 |
| UNSUPPORTED | 서버에서 `지금은 지원하지 않는 기능입니다` + 빈 추천으로 반환. 채널 도구 호출 생략 |

`PolicyEvaluation`의 JSON 구성요소는 `status`, `matches`, `missingFields`, `message`입니다. 각 `matches[].policy`가 PolicyResult입니다. Java의 `.policies()`는 편의 메서드이지 JSON의 별도 최상위 필드를 약속하는 것은 아닙니다.

`verifiedContent`는 이름과 달리 개발용/검토필요 상태도 포함한 JSON 문자열입니다. 그 필드가 있다는 이유로 검증 완료라고 해석하지 마세요. `matches[].evidenceLevel`, `branches[].applicability/evidenceStatus`를 함께 읽습니다.

## 4. 채널 입력용 고객 상황

```json
{
  "requestGoal": "SELF_SERVICE_GUIDANCE",
  "needsAccountSpecificCheck": false,
  "selfServiceAvailable": true,
  "selfServiceAttemptFailed": false,
  "urgent": false,
  "acceptsAsyncReply": false,
  "realTimeConversationNeeded": false,
  "preferredChannel": null
}
```

위 Boolean을 기본값으로 복사하지 않습니다. 미언급은 null이며 현재 메시지에서 파악 가능한 경우만 채웁니다. `requestGoal`은 GENERAL_GUIDANCE / SELF_SERVICE_GUIDANCE / ACCOUNT_SPECIFIC_CHECK / EXECUTION_REQUEST입니다. 짜증이나 긴 문장만으로 긴급이라고 판단하지 않습니다.

`identityVerificationAvailable`은 고객의 인증 수단 이용 가능 여부이며 실제 인증 완료가 아닙니다. 상담 채팅에 비밀번호·신분증 원본·OTP를 받지 않습니다.

도구가 받는 입력에는 점수·가중치·채널 운영 상태를 넣지 않습니다. `PolicyResult` 전체를 LLM이 재작성해서 넘기게 하지 말고 래퍼에서 조회 결과를 사용합니다. B도 policyCode로 다시 조회해 검증합니다.

## 5. 채널 결과 처리

| status | A 처리 |
| --- | --- |
| RANKED | recommendations를 그대로 최종 AgentResult에 사용 |
| NEEDS_INFORMATION / NEEDS_CONTEXT | 현재 메시지에서 부족한 조건을 안내하고 빈 추천 반환. 이전 요청 정보를 보충하지 않음 |
| REVIEW_REQUIRED / CONTACT_SCOPE_REVIEW_REQUIRED | 근거·담당기관 확인이 필요함을 안내. 임의 기관/번호 생성 금지 |
| NO_ELIGIBLE_CHANNEL | 검증된 추천이 없음. decisions의 UNKNOWN/INELIGIBLE 사유를 설명. 전화가 자동 정답인 것은 아님 |
| UNSUPPORTED | 미지원 고정 문구, 빈 추천 |
| POLICY_REFERENCE_MISMATCH | 도구 호출 인자/정책 상태를 재확인. 모델이 만든 정책을 수용하지 않음 |
| EXECUTION_NOT_IMPLEMENTED | 실제 실행을 하지 않았음을 안내하고 필요한 공식 절차 범위를 설명 |

`recommendations`에는 처리 가능 채널만 들어갑니다. `decisions`에는 미확인/제외 이유, 공동 순위가 있습니다. scoringVersion은 공통 평가식 버전입니다. 모델이 순위·점수·URL을 새로 만들면 안 됩니다.

## 6. 최종 응답 조합

```java
// answer/summary만 모델 출력에서 파싱·검증한다.
// 추천은 현재 요청의 마지막 검증 결과에서 가져온다.
return new AgentResult(answer, tools.verifiedRecommendations(), summary);
```

예시 래퍼는 **요청마다 새로 생성**해야 합니다. 마지막 도구 결과를 singleton 필드에 보관하면 고객 간 정보가 섞입니다. sessionId는 C의 활성 세션 검증용으로 유지할 수 있지만, ChatMemory와 이전 facts/context 저장·병합에는 사용하지 않습니다.

최종 answer도 도구 근거와 맞는지 검증해야 합니다. 특히 UNSUPPORTED는 모델 자유 생성 없이 고정 문구로 확정하고, 도구가 실패했으면 이전 성공 추천을 그대로 내보내지 않습니다.

현재 AgentResult에는 최상위 officialPageUrl이 없습니다. URL은 추천 항목마다 있고, 추천이 없는 준비 안내의 출처는 answer 안의 링크로 안내하거나 A/C와 새 계약을 합의해야 합니다. 임의 필드 추가는 하지 않습니다.

## 7. 예외와 운영 정보

LLM이 잘못된 facts 타입·코드를 보내 B가 IllegalArgumentException을 던진 경우, 원래 고객 HTTP 메시지가 잘못됐다는 뜻은 아닙니다. A가 제한된 재추출 또는 명시적 에이전트 오류로 변환해야 합니다. DB 장애를 모델 재시도로 덮거나 미지원으로 바꾸지 않습니다.

`policy-demo` 프로필은 DRAFT 정책 + 모의 전화/이메일 지원·가용성입니다. 이유에 있는 모의/개발용 표시를 삭제하지 않습니다. 실제 은행 운영 API가 연결됐다는 표현을 하지 않습니다.

## 현재 메시지만 판단하는 A 구현 규칙

- 전용 ChatClient는 ChatMemory advisor 및 과거 messages를 등록하지 않은 상태로 구성합니다. 호출부에서 conversationId만 생략해도 기본 memory advisor가 남아 있으면 충분하지 않습니다.
- 입력은 시스템 지침 + 이번 message입니다. 이번 실행의 도구 결과는 답변 근거로 사용할 수 있습니다.
- facts/context는 요청마다 새로 구성합니다. 미언급은 null/누락이며 고객 상황을 기본값으로 채우지 않습니다.
- selfServiceAttemptFailed는 현재 message에서 고객이 설명한 실패 경험입니다. 과거 채팅을 읽는 필드가 아닙니다.
- B는 원문을 받지 않으므로 사실이 현재 메시지에서 나온 것인지 확인할 수 없습니다. A가 추출 근거를 현재 message와 대조해야 합니다.
- 정보가 부족하면 추천을 확정하지 않습니다. 예: “자동이체 날짜 변경 문의라면, 같은 은행으로 보내는지 다른 은행으로 보내는지도 함께 적어주세요.” 다음 입력이 “다른 은행이요”뿐이면 이전 요청을 복원하지 않습니다.
- 현재 메시지로 업무 자체를 식별하지 못하면 A가 입력 보완을 안내합니다. 업무를 식별했지만 등록 정책이 없으면 B의 UNSUPPORTED를 따라 고정 문구를 반환합니다.

시스템 프롬프트에 포함할 내용:
```text
고객 상황은 현재 사용자 메시지에서만 추출한다. 이전 요청이나 응답은 사용하지 않는다.
정책 근거는 정책 도구 결과, 채널 지원·점수·순위는 채널 도구 결과만 사용한다.
현재 메시지에 없는 사실은 null 또는 누락으로 둔다.
누락된 필수 조건은 현재 업무와 함께 다시 입력하도록 안내하며 추천을 확정하지 않는다.
이번 요청 안에서 조회한 도구 결과만 최종 답변에 사용한다.
```
