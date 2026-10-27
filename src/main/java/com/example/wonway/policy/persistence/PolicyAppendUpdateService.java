package com.example.wonway.policy.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.example.wonway.policy.persistence.PolicyJsonDocument.*;

/** Explicit append-only policy upgrade. Refuses changed/deleted legacy rows or an unexpected DB baseline. */
@Service
public class PolicyAppendUpdateService {
    @PersistenceContext private EntityManager em;
    private final ObjectMapper mapper = new ObjectMapper();

    @Transactional
    public PolicyImportService.ImportResult append(byte[] baselineBytes, byte[] newBytes) {
        var baseline = parse(baselineBytes);
        var next = parse(newBytes);
        if (!baseline.metadata().equals(next.metadata())) throw new IllegalArgumentException("Metadata changed; separate migration required");
        var current = em.find(PolicyDatasetEntity.class, "CURRENT", LockModeType.PESSIMISTIC_WRITE);
        if (current == null) throw new IllegalStateException("No baseline dataset in DB; import first");
        if (current.getChecksum().equals(next.checksum())) return result("ALREADY_UPDATED", next);
        if (!current.getChecksum().equals(baseline.checksum())) throw new IllegalStateException("DB baseline checksum differs; refusing update");
        try {
            if (!mapper.readTree(current.getMetadataJson()).equals(baseline.metadata())) {
                throw new IllegalStateException("DB metadata differs from baseline");
            }
        } catch (java.io.IOException e) { throw new IllegalStateException("Invalid DB metadata", e); }

        verifyRows("V4PolicySourceEntity", baseline.root().path("sources"), next.root().path("sources"), "sourceId");
        verifyRows("V4ConsultationPolicyEntity", baseline.root().path("policies"), next.root().path("policies"), "policyCode");
        verifyRows("V4PolicyBranchEntity", baseline.root().path("branches"), next.root().path("branches"), "branchCode");
        verifyRows("V4PolicyProjectRuleEntity", baseline.root().path("projectRules"), next.root().path("projectRules"), "code");
        var oldLinks = links(baseline.root());
        var dbLinks = em.createQuery("select b.branchCode, b.sourceId from V4BranchSourceEntity b", Object[].class).getResultList();
        var actualLinks = new java.util.HashSet<String>();
        dbLinks.forEach(row -> actualLinks.add(row[0] + "\n" + row[1]));
        if (!actualLinks.equals(oldLinks.keySet())) throw new IllegalStateException("DB source links differ from baseline");

        for (JsonNode s : additions(baseline.root(), next.root(), "sources", "sourceId").values()) {
            em.persist(new PolicySourceEntity(required(s,"sourceId"),required(s,"title"),nullableText(s,"sourceUrl"),required(s,"evidenceStatus"),s.toString()));
        }
        for (JsonNode p : additions(baseline.root(), next.root(), "policies", "policyCode").values()) {
            em.persist(new ConsultationPolicyEntity(required(p,"policyCode"),required(p,"intent"),required(p,"title"),required(p,"approvalStatus"),date(p,"effectiveFrom"),date(p,"effectiveTo"),p.toString()));
        }
        em.flush();
        for (JsonNode b : additions(baseline.root(), next.root(), "branches", "branchCode").values()) {
            em.persist(new PolicyBranchEntity(required(b,"branchCode"),required(b,"policyCode"),b.path("conditions").toString(),b.path("consultationAssessment").toString(),b.toString()));
        }
        em.flush();
        links(next.root()).forEach((key, pair) -> {
            if (!oldLinks.containsKey(key)) em.persist(new BranchSourceEntity(pair[0], pair[1]));
        });
        for (JsonNode r : additions(baseline.root(), next.root(), "projectRules", "code").values()) {
            em.persist(new PolicyProjectRuleEntity(required(r,"code"),r.toString()));
        }
        current.updateChecksum(next.checksum());
        em.flush();
        return result("UPDATED", next);
    }

    private void verifyRows(String entity, JsonNode oldRows, JsonNode newRows, String key) {
        var expected = indexed(oldRows, key);
        var replacement = indexed(newRows, key);
        expected.forEach((id, row) -> {
            if (!row.equals(replacement.get(id))) throw new IllegalArgumentException("Legacy row changed or removed: " + id);
        });
        var actual = new LinkedHashMap<String, JsonNode>();
        for (String json : em.createQuery("select e.payloadJson from " + entity + " e", String.class).getResultList()) {
            try {
                var row = mapper.readTree(json);
                actual.put(required(row, key), row);
            } catch (java.io.IOException e) { throw new IllegalStateException("Invalid DB row", e); }
        }
        if (!actual.equals(expected)) throw new IllegalStateException("DB rows differ from baseline: " + entity);
    }

    private static Map<String, JsonNode> indexed(JsonNode rows, String key) {
        var result = new LinkedHashMap<String, JsonNode>();
        rows.forEach(row -> result.put(required(row, key), row));
        return result;
    }

    private static Map<String, JsonNode> additions(JsonNode oldRoot, JsonNode nextRoot, String field, String key) {
        var result = indexed(nextRoot.path(field), key);
        indexed(oldRoot.path(field), key).keySet().forEach(result::remove);
        return result;
    }

    private static Map<String, String[]> links(JsonNode root) {
        var result = new LinkedHashMap<String, String[]>();
        for (var branch : root.path("branches")) for (var source : branch.path("sourceIds")) {
            String code = required(branch, "branchCode"), id = source.asText();
            result.put(code + "\n" + id, new String[]{code, id});
        }
        return result;
    }

    private static PolicyImportService.ImportResult result(String status, PolicyJsonDocument doc) {
        var root = doc.root();
        return new PolicyImportService.ImportResult(status, root.path("sources").size(), root.path("policies").size(),
                root.path("branches").size(), root.path("projectRules").size(), doc.checksum());
    }
}
