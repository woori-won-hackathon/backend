# 정책 데이터 저장 (B 담당)

현재 프로젝트의 Spring Boot 4.1.2 / Java 21 / MySQL 설정에 맞춘 정책 초기 적재 코드입니다.
공용 DTO, build.gradle, application.yaml, A/C 패키지 및 서비스 인터페이스는 변경하지 않았습니다.

## 저장 구조

- kb_dataset: 1건. JSON 버전, 파일 체크섬, 전역 계약/제한 등 원본 메타데이터
- kb_source: 37건. FAQ 출처 및 원본 필드
- kb_policy: 26건. 정책 의도, 기간, 승인 상태 및 원본 필드
- kb_branch: 52건. 상황 조건, 상담 시 평가 항목, 처리 절차 및 원본 필드
- kb_branch_source: 60건. 분기-출처 연결
- kb_project_rule: 5건. 공통 프로젝트 원칙

조건·절차는 TEXT 컬럼의 JSON으로 보존합니다. 검색용 컬럼과 원본 payload_json을 함께 저장합니다.
공식 FAQ의 업무 제한은 보존하고, 고정된 상담 채널·점수·순위는 저장하지 않습니다.

## 코드와 실행

Java 코드: com.example.wonway.policy.persistence 패키지.
PolicyImportService가 JPA를 사용해 전체 데이터를 하나의 트랜잭션으로 저장합니다.
동일 파일은 ALREADY_IMPORTED로 종료합니다. 다른 파일이나 부분 적재 상태는 자동 덮어쓰지 않고 오류로 처리합니다.

현재 프로젝트의 ddl-auto:update가 엔티티로 테이블과 외래키를 생성합니다.
schema-mysql.sql은 새 DB용 수동 생성 참고 파일이며 자동 실행되지 않습니다. 이미 생성된 DB에 그대로 재실행하지 않습니다.
테이블 생성 DDL은 MySQL 특성상 데이터 저장 트랜잭션과 별개입니다. 데이터 적재 실패 시 테이블은 남을 수 있고 행 저장은 롤백됩니다.

PolicyDataInitializer는 local/dev 프로필이며 policy.seed.enabled=true일 때만 실행됩니다. 기본값은 false입니다.
초기 적재 검증은 다음처럼 명시적으로 실행합니다. 이 명령은 실제 설정된 로컬 wonway DB에 데이터를 커밋합니다.

```sh
POLICY_MYSQL_IMPORT=true ./gradlew test --tests 'com.example.wonway.policy.persistence.PolicyMySqlImportTest' --rerun-tasks
```

일반 테스트에서는 위 MySQL 적재 테스트가 건너뛰어집니다. JSON 검증만 실행하려면:

```sh
./gradlew test --tests 'com.example.wonway.policy.persistence.PolicyJsonDocumentTest'
```

검증일: 2026-10-27. 실제 wonway MySQL에서 테이블별 건수, 모든 정책 JSON 객체와 메타데이터 보존,
출처 연결, 외래키 3개, 동일 파일 중복 방지, JPA 조회, 운영 조회에서 DRAFT 제외를 확인했습니다.
환경 파일과 비밀키는 출력하지 않습니다. 기존 애플리케이션 설정이 연결 정보를 사용합니다.

## 이후 연결

PolicyKnowledgeRepository는 고정 JPQL로 정책·분기·출처를 조회합니다.
B의 PolicyServiceImpl에서 이를 사용해 상황 조건 검증 및 공용 PolicyResult 변환을 구현하면 됩니다.
PolicyServiceImpl/ChannelRankingServiceImpl과 A의 도구 연결은 이번 저장 작업 범위에 포함하지 않았습니다.
현재 공용 PolicySearchCondition에는 나이·방문자·시도 단계 등 상황 전달 필드가 부족하므로 팀 계약 합의가 필요합니다.
모든 정책은 DRAFT입니다. 운영 승인으로 자동 승격하지 않습니다.

## MySQL 확인

```sql
USE wonway;
SHOW TABLES LIKE 'kb_%';
SELECT COUNT(*) FROM kb_policy;
SELECT COUNT(*) FROM kb_branch;
SELECT policy_code, intent, title FROM kb_policy ORDER BY policy_code;
```
