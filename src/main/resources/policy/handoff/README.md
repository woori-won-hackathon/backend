# B → A/C 전달 안내

기준: 2026-10-27, 실제 `/Users/eunhyekim/backend`, `feature/tool` 브랜치.
Spring Boot 4.1.2 / Spring AI 2.0.1 / Java 21 / MySQL.

## 먼저 전달할 것

**소스는 A/C에게 각각 일부만 보내기보다 B 작업 전체를 같은 Git 커밋으로 공유하세요.** 서비스 구현은 persistence/model/ranking/resource에 의존합니다. 인터페이스만 가져오면 실행되지 않습니다. 현재 B 작업은 로컬 변경 상태이며 커밋·push는 수행하지 않았습니다. 상대방이 `git pull`만 해서는 아직 받을 수 없습니다. 별도 ZIP도 제공하며 `.env`, 비밀키, `.git`, 빌드 결과는 포함하지 않습니다.

| 대상 | 우선 읽을 문서 | 확인할 핵심 |
| --- | --- | --- |
| A | A_TOOL_CONNECTION.md + ConsultationTools.java.example + tool-input-catalog.json | 도구 등록, 사실 추출, 상태 분기, 서버 추천 결과 유지 |
| C | C_API_CONNECTION.md | A 호출, EMAIL 매핑, 업무 상태/오류 구분, 세션 처리 |
| 공통 | ../SERVICE_FLOW.md | 정책 검증·평가 기준, 데모 설정, 테스트 |

## 코드 전달 범위 (backend 기준 경로)

### 양쪽이 동일 버전으로 받아야 하는 공용 파일

- `src/main/java/com/example/wonway/common/contract/PolicySearchCondition.java`: facts 추가, 기존 4인자 생성자 유지
- `.../ChannelRankingCondition.java`: facts/context 추가, 기존 5인자 생성자 유지
- `.../ConsultationContext.java`: 새 고객 상황 DTO
- `.../ChannelType.java`: EMAIL 추가, 기존 값 유지
- 기존 `PolicyResult.java`, `ChannelRecommendation.java`는 필드 변경 없음. 숫자 policyId는 현재 null이며 policyCode를 사용

### B 구현 전체

- `src/main/java/com/example/wonway/policy/service/**`
- `src/main/java/com/example/wonway/policy/model/**`
- `src/main/java/com/example/wonway/policy/persistence/**`
- `src/main/java/com/example/wonway/policy/ranking/**`
- `src/main/resources/policy/**`
- `src/main/resources/application-policy-demo.yaml`
- `src/test/java/com/example/wonway/policy/**`

`PolicyService`와 `ChannelRankingService`에 상세 결과용 evaluate 메서드를 추가했습니다. A는 인터페이스를 주입받으면 됩니다. 구현체를 직접 new 하거나 DB 저장소를 도구로 노출하지 않습니다.

## A/C의 로컬 실행 준비

1. 팀의 기존 비밀 설정 방식으로 각자 DB/API 설정을 준비합니다. B의 `.env`를 전달하지 않습니다.
2. 각자 로컬 MySQL에 `wonway` DB가 있어야 합니다. B 노트북에 저장된 테이블이 Git으로 공유되지는 않습니다.
3. 정책 첫 적재는 `../README.md`의 명시적 적재 방법을 따릅니다. 기존 데이터가 다르면 자동 덮어쓰지 않습니다.
4. 시연은 IntelliJ Active profiles에 `policy-demo` 지정. 이 프로필은 DRAFT 정책과 **모의** 전화·이메일 운영정보를 사용합니다. 초기 적재를 자동 수행하는 프로필은 아닙니다.
5. 실제 운영에서는 전화·이메일 지원 범위/가용성 제공자를 교체해야 합니다. 기본 모드는 미확인 상태를 가능한 것으로 취급하지 않습니다.

## 공동 인수 확인

- 첫 발화에서 누락된 조건을 false로 채우지 않고 NEEDS_INFORMATION으로 받는가?
- 같은 sessionId로 다음 요청을 보내도 이전 facts/context/추천이 섞이지 않는가?
- 같은 자동이체 업무에서 일반 안내/개별 확인/비동기 지원 상황에 따라 추천이 달라지는가?
- 미지원이면 정확한 고정 문구와 빈 추천을 반환하는가?
- 정책 검색 0건과 DB/모델 장애를 구분하는가?
- 추천 채널·점수·이유·URL을 모델이 바꾸거나 추가하지 못하는가?
- API의 EMAIL/CHATBOT 문자열이 프론트 표시와 연결되는가?
- 실제로 실행하지 않은 거래를 완료했다고 말하지 않는가?
- A가 ChatMemory와 과거 messages를 사용하지 않고 현재 message만 모델에 전달하는가?

기존 B 정책·채널·MySQL 검증은 통과했으며, 현재 메시지 독립성 회귀 테스트를 추가했습니다. 실제 LLM tool call과 C의 HTTP 흐름은 A/C 통합 후 검증해야 합니다.

## 전달 예시 검증

ConsultationTools.java.example는 실제 backend의 Spring AI 2.0.1 의존성으로 Java 21 컴파일을 확인했습니다. ToolCallbacks를 사용해 두 도구의 입력 스키마 생성, 선택 인자 생략, UNSUPPORTED 반환, 미지원 때 채널 서비스 미호출을 검증했습니다. 테스트에는 stub 서비스를 사용했으며 외부 LLM/API 호출은 하지 않았습니다. 이 예시 파일은 아직 A의 agent 패키지에 적용되지 않았습니다.
