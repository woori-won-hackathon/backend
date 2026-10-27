# C 담당자: API·세션과 B 결과 연결

## C가 호출하는 것은 A의 AgentService

```text
프론트 message
 → C: 메시지 검증 + 활성 sessionId 확인
 → A: AgentService.chat(sessionId, message)
 → A 내부에서 B의 정책·채널 도구 사용
 → A: AgentResult
 → C: ConsultationMessageResponse로 변환
```

C가 고객 메시지를 직접 PolicyService로 보내거나, 정책 조회와 채널 추천을 다시 실행할 필요는 없습니다. LLM 도구 실행은 A, 정책 판단·순위 계산은 B가 담당합니다.

## 실제 프로젝트 응답 계약

```java
// agent/model/AgentResult
AgentResult(String answer,
            List<ChannelRecommendation> recommendations,
            String consultationSummary)

// api/dto/response/ConsultationMessageResponse
ConsultationMessageResponse(UUID sessionId, String answer,
            List<ChannelRecommendationResponse> recommendations,
            String consultationSummary)

// api/dto/response/ChannelRecommendationResponse
ChannelRecommendationResponse(String channel, int score,
            List<String> reasons, String officialPageUrl)
```

최상위 officialPageUrl은 없습니다. 각 recommendations 항목의 URL을 사용합니다. URL이 null이면 프론트에서 링크 버튼을 숨깁니다.

Controller의 서비스 호출 후 매핑 예시:

```java
sessionService.validateActive(sessionId);
AgentResult result = agentService.chat(sessionId, request.message());
var recommendations = result.recommendations().stream()
    .filter(ChannelRecommendation::processable)
    .map(r -> new ChannelRecommendationResponse(
        r.channel().name(), r.score(), r.reasons(), r.officialPageUrl()))
    .toList();
return new ConsultationMessageResponse(
    sessionId, result.answer(), recommendations, result.consultationSummary());
```

추천의 순서·점수·이유·URL을 C에서 변경하지 않습니다. 가능한 항목이 하나면 하나, 없으면 빈 배열을 보냅니다. 3개를 채우려고 임의 채널을 추가하지 않습니다. 동점은 B의 상세 결과에서 공동 순위이며 현재 HTTP DTO에는 rank 필드가 없습니다. 프론트는 같은 점수의 순위를 같게 표시하거나 순위 표시를 생략합니다.

## 프론트와 공유할 채널 코드

| API 문자열 | 화면 표시 |
| --- | --- |
| CHATBOT | 챗 상담 |
| PHONE | 전화 상담 |
| EMAIL | 이메일 상담 |

`CHAT`이 아니라 `CHATBOT`입니다. 기존 enum의 MOBILE_APP/WEBSITE/BRANCH는 호환을 위해 남았지만 현재 B 평가기의 출력 대상은 위 3개입니다. 새 EMAIL을 switch/프론트 라벨에서도 처리해야 합니다.

## 업무 상태와 HTTP 오류를 구분

| 상황 | 처리 방향 |
| --- | --- |
| 정상 추천 | 200, 추천 배열 |
| 정책 미지원 | 200, `지금은 지원하지 않는 기능입니다`, 추천 [] |
| 추가 확인 필요 | 200, A가 작성한 최소 확인 질문, 추천 [] |
| 근거 검토 필요/추천할 채널 없음 | 200, A의 상태 설명, 추천 [] |
| HTTP message 누락·공백·1000자 초과 | 400, Bean Validation |
| 존재하지 않는 세션 | 404 (C 세션 예외 타입과 합의) |
| 종료한 세션 | 409 (C 세션 예외 타입과 합의) |
| 모델 장애·도구 출력 처리 실패 | A가 에이전트 예외로 변환, 502 또는 일시 장애 503 |
| DB 접속 장애 | 일시 서비스 장애 503 등 공통 규칙 적용 |
| 저장된 정책 JSON 손상·서버 내부 결함 | 500, 내부 상세 비노출 |

위 HTTP 매핑은 C 구현 가이드이며 예외 클래스/GlobalExceptionHandler가 이미 작성됐다는 뜻이 아닙니다. B는 현재 IllegalArgumentException(도구 입력 형식), IllegalStateException(정책 데이터/내부 상태), DB 예외를 사용합니다. **모든 IllegalArgumentException을 400으로 매핑하지 마세요.** 고객은 올바른 메시지를 보냈지만 LLM이 facts 타입을 잘못 생성했을 수도 있으므로 A가 단계별 예외로 구분해야 합니다.

## 세션 종료

기존 상담 종료 API는 종료 상태를 표시합니다. 이번 범위는 현재 메시지만 판단하므로 ChatMemory를 생성하거나 읽지 않으며, 메모리 삭제를 새 필수 기능으로 구현하지 않습니다. **정책 DB(kb_ 테이블)는 삭제하지 않습니다.** 동시 메시지·종료 요청의 충돌은 C의 세션 관리에서 처리합니다. 로그에는 전체 상담 원문·계좌번호·비밀키를 남기지 않습니다.

## 검증 요청

1. C 단위 테스트에서는 AgentService를 mock하여 HTTP 상태·응답 매핑을 검증.
2. EMAIL, 빈 추천, URL null, 동일 점수 결과를 응답에 그대로 전달하는지 확인.
3. 세션 A/B 분리와 종료 후 메시지 거절 확인. 같은 세션의 이전 메시지도 A 입력에 합치지 않는지 확인.
4. A가 실제 도구를 연결하면 같은 sessionId로 완전한 문의 → 조건이 빠진 독립 문의를 보내 이전 정보가 보충되지 않는지 통합 검증.

`policy-demo` 프로필의 전화·이메일 가용성은 모의 데이터입니다. 프론트에 실제 상담 연결·예약·거래 완료로 표시하지 않습니다.
