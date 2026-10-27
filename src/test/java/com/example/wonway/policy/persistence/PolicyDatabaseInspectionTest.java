package com.example.wonway.policy.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Explicit read-only inspection of the configured wonway database; never imports or changes schema. */
@EnabledIfEnvironmentVariable(named = "POLICY_MYSQL_INSPECT", matches = "true")
@SpringBootTest(properties = {
        "spring.main.web-application-type=none", "policy.seed.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none", "spring.sql.init.mode=never",
        "spring.ai.model.chat=none", "spring.ai.model.embedding=none", "spring.ai.model.image=none",
        "spring.ai.model.audio.transcription=none", "spring.ai.model.audio.speech=none",
        "spring.ai.model.moderation=none"
})
class PolicyDatabaseInspectionTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    @Transactional(readOnly = true)
    void inspectsCountsAndContentWithoutWriting() throws Exception {
        String database = jdbc.queryForObject("select database()", String.class);
        assertEquals("wonway", database, "Expected the configured local wonway database");
        var mappings = Map.of("sources", "kb_source", "policies", "kb_policy", "branches", "kb_branch",
                "projectRules", "kb_project_rule");
        Set<String> tables = new HashSet<>(jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema=database()", String.class));
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root;
        try (var in = getClass().getResourceAsStream("/policy/policies.json")) {
            root = mapper.readTree(in);
        }
        boolean same = true;
        for (var entry : mappings.entrySet()) {
            if (!tables.contains(entry.getValue())) {
                System.out.println("POLICY_DB_TABLE " + entry.getValue() + " MISSING");
                same = false;
                continue;
            }
            var rows = jdbc.queryForList("select payload_json from " + entry.getValue(), String.class);
            Set<JsonNode> actual = new HashSet<>();
            for (String row : rows) actual.add(mapper.readTree(row));
            Set<JsonNode> expected = new HashSet<>();
            root.path(entry.getKey()).forEach(expected::add);
            boolean matches = rows.size() == expected.size() && actual.equals(expected);
            same &= matches;
            System.out.println("POLICY_DB_TABLE " + entry.getValue() + " count=" + rows.size() + " matches=" + matches);
        }
        if (tables.contains("kb_dataset")) {
            System.out.println("POLICY_DB_DATASETS " + jdbc.queryForList(
                    "select dataset_key, schema_version, checksum from kb_dataset"));
        }
        if (tables.contains("kb_branch_source")) {
            int expectedLinks = 0;
            for (var branch : root.path("branches")) expectedLinks += branch.path("sourceIds").size();
            int links = jdbc.queryForObject("select count(*) from kb_branch_source", Integer.class);
            same &= links == expectedLinks;
            System.out.println("POLICY_DB_LINKS count=" + links + " expected=" + expectedLinks);
        } else {
            same = false;
        }
        System.out.println("POLICY_DB_MATCHES_FILE " + same);
    }
}
