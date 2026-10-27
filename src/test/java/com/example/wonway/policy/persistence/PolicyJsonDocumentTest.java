package com.example.wonway.policy.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PolicyJsonDocumentTest {
    private byte[] input() throws Exception {
        try(var in=getClass().getResourceAsStream("/policy/policies.json")) {return in.readAllBytes();}
    }
    @Test void latestJsonContainsAllDataWithoutFixedChannels() throws Exception {
        var doc=PolicyJsonDocument.parse(input());
        assertEquals(27,doc.root().path("policies").size());
        assertEquals(53,doc.root().path("branches").size());
        assertEquals(39,doc.root().path("sources").size());
        for(var b:doc.root().path("branches")) {
            assertFalse(b.has("projectRouting"));
            assertEquals("RUNTIME_CONTEXT_EVALUATION",b.path("consultationAssessment").path("mode").asText());
        }
        assertTrue(doc.metadata().has("channelEvaluationContract"));
    }
    @Test void invalidReferencesFailBeforeWriting() throws Exception {
        var mapper=new ObjectMapper();
        var root=(ObjectNode)mapper.readTree(input());
        ((ObjectNode)root.path("branches").get(0)).put("policyCode","NOT_FOUND");
        assertThrows(IllegalArgumentException.class,()->PolicyJsonDocument.parse(mapper.writeValueAsBytes(root)));
    }
}
