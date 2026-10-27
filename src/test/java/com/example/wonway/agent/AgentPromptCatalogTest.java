package com.example.wonway.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.assertj.core.api.Assertions.assertThat;

class AgentPromptCatalogTest {
    @Test
    void aiCatalogIncludesAllPolicyCodesAndTheNewIntrabankConditions() throws Exception {
        var mapper = new ObjectMapper();
        var catalog = mapper.readTree(AgentPromptCatalog.load());
        JsonNode policies;
        try (var in = getClass().getResourceAsStream("/policy/policies.json")) { policies = mapper.readTree(in); }
        var expected = new HashSet<String>();
        policies.path("policies").forEach(policy -> expected.add(policy.path("policyCode").asText()));
        var actual = new HashSet<String>();
        catalog.path("policies").forEach(policy -> actual.add(policy.path("policyCode").asText()));
        assertThat(actual).isEqualTo(expected);
        var intrabank = java.util.stream.StreamSupport.stream(catalog.path("policies").spliterator(), false)
                .filter(policy -> policy.path("policyCode").asText().equals("INTRABANK_AUTO")).findFirst().orElseThrow();
        assertThat(intrabank.path("intent").asText()).isEqualTo("INTRABANK_AUTO_TRANSFER");
        assertThat(intrabank.path("conditionBranches").get(0).path("conditions").toString()).contains("INTRABANK", "CHANGE_DATE");
    }
}
