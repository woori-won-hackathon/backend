-- MySQL 8 신규 테이블 수동 생성용 참고 SQL. 자동 실행되지 않는다.
-- 기존 application.yaml의 ddl-auto:update는 JPA 엔티티로 동일 구조를 생성한다.
CREATE TABLE kb_dataset (
  dataset_key VARCHAR(40) PRIMARY KEY,
  schema_version INTEGER NOT NULL,
  checksum VARCHAR(64) NOT NULL,
  metadata_json TEXT NOT NULL
);
CREATE TABLE kb_source (
  source_id VARCHAR(100) PRIMARY KEY,
  title VARCHAR(500) NOT NULL,
  source_url TEXT,
  evidence_status VARCHAR(60) NOT NULL,
  payload_json TEXT NOT NULL
);
CREATE TABLE kb_policy (
  policy_code VARCHAR(100) PRIMARY KEY,
  intent VARCHAR(100) NOT NULL,
  title VARCHAR(500) NOT NULL,
  approval_status VARCHAR(40) NOT NULL,
  effective_from DATE,
  effective_to DATE,
  payload_json TEXT NOT NULL,
  CONSTRAINT ck_kb_policy_dates CHECK (effective_from IS NULL OR effective_to IS NULL OR effective_to >= effective_from)
);
CREATE INDEX ix_kb_policy_intent ON kb_policy(intent);
CREATE TABLE kb_branch (
  branch_code VARCHAR(120) PRIMARY KEY,
  policy_code VARCHAR(100) NOT NULL REFERENCES kb_policy(policy_code),
  conditions_json TEXT NOT NULL,
  assessment_json TEXT NOT NULL,
  payload_json TEXT NOT NULL
);
CREATE INDEX ix_kb_branch_policy ON kb_branch(policy_code);
CREATE TABLE kb_branch_source (
  branch_code VARCHAR(120) NOT NULL REFERENCES kb_branch(branch_code),
  source_id VARCHAR(100) NOT NULL REFERENCES kb_source(source_id),
  PRIMARY KEY (branch_code, source_id)
);
CREATE TABLE kb_project_rule (
  rule_code VARCHAR(100) PRIMARY KEY,
  payload_json TEXT NOT NULL
);
