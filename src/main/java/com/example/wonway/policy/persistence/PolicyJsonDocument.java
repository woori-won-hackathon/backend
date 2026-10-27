package com.example.wonway.policy.persistence;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.security.*;
import java.time.LocalDate;
import java.util.*;

/** 서버에서 관리하는 정책 파일만 적재한다. LLM 출력이나 고객 입력을 받지 않는다. */
public record PolicyJsonDocument(ObjectNode root, String checksum) {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public static PolicyJsonDocument parse(byte[] bytes) {
        try {
            JsonNode parsed=MAPPER.readTree(bytes);
            if (!(parsed instanceof ObjectNode root) || !root.path("version").isIntegralNumber() || root.path("version").asInt(-1)!=4)
                throw new IllegalArgumentException("정책 JSON version=4 객체가 필요합니다.");
            for (String key: List.of("sources","policies","branches","projectRules"))
                if (!root.path(key).isArray()) throw new IllegalArgumentException("배열 누락: "+key);
            Set<String> sources=ids(root.path("sources"),"sourceId");
            Set<String> policies=ids(root.path("policies"),"policyCode");
            ids(root.path("branches"),"branchCode"); ids(root.path("projectRules"),"code");
            for (JsonNode s:root.path("sources")) { required(s,"title"); required(s,"evidenceStatus"); }
            for (JsonNode p:root.path("policies")) {
                required(p,"intent"); required(p,"title"); required(p,"approvalStatus");
                LocalDate from=date(p,"effectiveFrom"), to=date(p,"effectiveTo");
                if (from!=null && to!=null && to.isBefore(from)) throw new IllegalArgumentException("정책 적용 기간 역전");
            }
            for (JsonNode b:root.path("branches")) {
                if (!policies.contains(required(b,"policyCode"))) throw new IllegalArgumentException("존재하지 않는 policyCode");
                if (!b.path("sourceIds").isArray() || b.path("sourceIds").isEmpty()) throw new IllegalArgumentException("분기 sourceIds 누락");
                Set<String> refs=new HashSet<>();
                for (JsonNode ref:b.path("sourceIds"))
                    if (!ref.isTextual() || !sources.contains(ref.asText()) || !refs.add(ref.asText()))
                        throw new IllegalArgumentException("없는 출처 또는 중복 출처 참조");
                if (!b.path("conditions").path("all").isArray()) throw new IllegalArgumentException("conditions.all 누락");
                if (!"RUNTIME_CONTEXT_EVALUATION".equals(b.path("consultationAssessment").path("mode").asText()))
                    throw new IllegalArgumentException("고객 상황 기반 평가 모드가 필요합니다.");
                if (b.has("projectRouting") || b.has("candidateChannels")) throw new IllegalArgumentException("v3 고정 채널 설정은 입력할 수 없습니다.");
            }
            return new PolicyJsonDocument(root, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("정책 JSON을 읽을 수 없습니다.",e);
        }
    }
    private static Set<String> ids(JsonNode array,String field) {
        Set<String> ids=new HashSet<>();
        for (JsonNode row:array) if (!ids.add(required(row,field))) throw new IllegalArgumentException("중복 식별자: "+field);
        return ids;
    }
    static String required(JsonNode n,String field) {
        JsonNode v=n.get(field);
        if (v==null || !v.isTextual() || v.asText().isBlank()) throw new IllegalArgumentException("필수 문자열 누락: "+field);
        return v.asText();
    }
    static String nullableText(JsonNode n,String field) { return n.path(field).isTextual()?n.path(field).asText():null; }
    static LocalDate date(JsonNode n,String field) {
        JsonNode v=n.get(field);
        if (v==null || v.isNull()) return null;
        return LocalDate.parse(required(n,field));
    }
    public ObjectNode metadata() {
        ObjectNode copy=root.deepCopy();
        copy.remove(List.of("sources","policies","branches","projectRules"));
        return copy;
    }
}
