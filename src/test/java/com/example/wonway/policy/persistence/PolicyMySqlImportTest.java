package com.example.wonway.policy.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 명시적으로 실행한 경우만 로컬 wonway DB에 정책을 커밋한다. 일반 test에서는 건너뛴다. */
@EnabledIfEnvironmentVariable(named="POLICY_MYSQL_IMPORT",matches="true")
@SpringBootTest(properties={
    "spring.main.web-application-type=none", "spring.profiles.active=local",
    "policy.seed.enabled=true", "spring.ai.model.chat=none", "spring.ai.model.embedding=none",
    "spring.ai.model.image=none", "spring.ai.model.audio.transcription=none", "spring.ai.model.audio.speech=none",
    "spring.ai.model.moderation=none"
})
class PolicyMySqlImportTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PolicyImportService importer;
    @Autowired PolicyKnowledgeRepository repository;

    @Test void commitsFullJsonToMySqlAndDoesNotDuplicate() throws Exception {
        assertEquals("wonway",jdbc.queryForObject("select database()",String.class));
        byte[] bytes;
        try(var in=getClass().getResourceAsStream("/policy/policies.json")) {bytes=in.readAllBytes();}
        var mapper=new ObjectMapper();
        var root=mapper.readTree(bytes);
        assertEquals("ALREADY_IMPORTED",importer.importJson(bytes).status());
        var mappings=Map.of("sources","kb_source","policies","kb_policy","branches","kb_branch","projectRules","kb_project_rule");
        for(var entry:mappings.entrySet()) {
            // 테이블 이름은 코드에 정의한 고정 목록만 사용.
            var rows=jdbc.queryForList("select payload_json from "+entry.getValue(),String.class);
            assertEquals(root.path(entry.getKey()).size(),rows.size());
            var actual=new java.util.HashSet<com.fasterxml.jackson.databind.JsonNode>();
            for(var row:rows) actual.add(mapper.readTree(row));
            for(var expected:root.path(entry.getKey())) assertTrue(actual.contains(expected));
        }
        int expectedLinks=0;for(var b:root.path("branches"))expectedLinks+=b.path("sourceIds").size();
        assertEquals(expectedLinks,jdbc.queryForObject("select count(*) from kb_branch_source",Integer.class));
        assertEquals(PolicyJsonDocument.parse(bytes).metadata(),mapper.readTree(jdbc.queryForObject("select metadata_json from kb_dataset where dataset_key='CURRENT'",String.class)));
        assertEquals(1,repository.findPolicies("INTERBANK_AUTO_TRANSFER",LocalDate.now(),true).size());
        assertTrue(repository.findPolicies("INTERBANK_AUTO_TRANSFER",LocalDate.now(),false).isEmpty());
        assertFalse(repository.findSourcesForBranch("INTERBANK_AUTO_GUIDE").isEmpty());
        int foreignKeys=jdbc.queryForObject("select count(*) from information_schema.referential_constraints where constraint_schema=database() and table_name in ('kb_branch','kb_branch_source')",Integer.class);
        assertEquals(3,foreignKeys);
    }
}
