package com.example.wonway.policy.persistence;

import com.example.wonway.common.contract.*;
import com.example.wonway.policy.model.PolicyEvaluation;
import com.example.wonway.policy.service.PolicyService;
import com.example.wonway.policy.service.ChannelRankingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Explicitly requested, transactional append migration on the configured wonway DB. */
@EnabledIfEnvironmentVariable(named = "POLICY_MYSQL_APPEND_UPDATE", matches = "true")
@SpringBootTest(properties = {
        "spring.main.web-application-type=none", "spring.profiles.active=policy-demo", "policy.seed.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none", "spring.sql.init.mode=never",
        "spring.ai.model.chat=none", "spring.ai.model.embedding=none", "spring.ai.model.image=none",
        "spring.ai.model.audio.transcription=none", "spring.ai.model.audio.speech=none", "spring.ai.model.moderation=none"
})
class PolicyAppendUpdateMySqlTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PolicyAppendUpdateService updater;
    @Autowired PolicyService policies;
    @Autowired ChannelRankingService channels;

    @Test
    void appendsOnlyNewRowsThenVerifiesCompleteSnapshotAndIntrabankRanking() throws Exception {
        assertEquals("wonway", jdbc.queryForObject("select database()", String.class));
        byte[] baseline, next;
        try (var in = getClass().getResourceAsStream("/policy/history/policies-before-intrabank.json")) { baseline = in.readAllBytes(); }
        try (var in = getClass().getResourceAsStream("/policy/policies.json")) { next = in.readAllBytes(); }
        System.out.println("POLICY_UPDATE_BEFORE policies=" + jdbc.queryForObject("select count(*) from kb_policy", Integer.class));
        var result = updater.append(baseline, next);
        assertTrue(result.status().equals("UPDATED") || result.status().equals("ALREADY_UPDATED"));
        assertEquals("ALREADY_UPDATED", updater.append(baseline, next).status());
        var mapper = new ObjectMapper();
        var root = mapper.readTree(next);
        for (var entry : Map.of("sources", "kb_source", "policies", "kb_policy", "branches", "kb_branch", "projectRules", "kb_project_rule").entrySet()) {
            var rows = jdbc.queryForList("select payload_json from " + entry.getValue(), String.class);
            var actual = new HashSet<JsonNode>();
            for (var row : rows) actual.add(mapper.readTree(row));
            var expected = new HashSet<JsonNode>();
            root.path(entry.getKey()).forEach(expected::add);
            assertEquals(expected.size(), rows.size());
            assertEquals(expected, actual);
            System.out.println("POLICY_UPDATE_TABLE " + entry.getValue() + " count=" + rows.size() + " matches=true");
        }
        var expectedLinks = new HashSet<String>();
        for (var branch : root.path("branches")) for (var id : branch.path("sourceIds")) {
            expectedLinks.add(branch.path("branchCode").asText() + "\n" + id.asText());
        }
        var actualLinks = new HashSet<>(jdbc.query("select branch_code, source_id from kb_branch_source",
                (row, index) -> row.getString(1) + "\n" + row.getString(2)));
        assertEquals(expectedLinks, actualLinks);
        assertEquals(PolicyJsonDocument.parse(next).checksum(), jdbc.queryForObject(
                "select checksum from kb_dataset where dataset_key='CURRENT'", String.class));
        var facts = Map.<String, Object>of("transferType", "INTRABANK", "operation", "CHANGE_DATE");
        var policy = policies.evaluate(new PolicySearchCondition("INTRABANK_AUTO_TRANSFER", "INDIVIDUAL", "INTRABANK_TRANSFER", null, facts));
        assertEquals(PolicyEvaluation.Status.MATCHED, policy.status());
        var context = new ConsultationContext("SELF_SERVICE_GUIDANCE", false, true, false, false, true, false, null);
        var ranked = channels.evaluate(new ChannelRankingCondition("INTRABANK_AUTO_TRANSFER", "INDIVIDUAL", "INTRABANK_TRANSFER", null, policy.policies(), facts, context));
        assertEquals("RANKED", ranked.status());
        assertEquals(ChannelType.CHATBOT, ranked.recommendations().getFirst().channel());
        System.out.println("POLICY_UPDATE_RESULT status=" + result.status() + " links=" + actualLinks.size()
                + " intrabank=" + policy.status() + " channels=" + ranked.recommendations().stream().map(r -> r.channel()).toList());
    }
}
