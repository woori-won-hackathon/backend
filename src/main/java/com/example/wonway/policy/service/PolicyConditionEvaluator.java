package com.example.wonway.policy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** 등록된 EQ/IN/BETWEEN만 비교. 조건 문자열이나 모델 생성 코드를 실행하지 않는다. */
public final class PolicyConditionEvaluator {
    public enum State { MATCH, MISMATCH, UNKNOWN }
    public record Result(State state,List<String> missingFields) {}
    private final ObjectMapper mapper=new ObjectMapper();

    public Result evaluate(JsonNode conditions,Map<String,Object> facts) {
        JsonNode all=conditions.path("all");
        if(!all.isArray() || all.isEmpty()) throw new IllegalStateException("조건 all 배열 누락");
        Set<String> missing=new TreeSet<>();
        boolean mismatch=false;
        for(JsonNode condition:all) {
            String field=condition.path("field").asText();
            String op=condition.path("op").asText();
            if(field.isBlank() || !Set.of("EQ","IN","BETWEEN").contains(op) || !condition.has("value"))
                throw new IllegalStateException("등록 정책의 조건 형식 오류");
            JsonNode expected=condition.get("value");
            if(op.equals("IN") && (!expected.isArray() || expected.isEmpty())) throw new IllegalStateException("IN 값 오류");
            if(op.equals("BETWEEN") && (!expected.isArray() || expected.size()!=2 || !expected.get(0).isNumber() || !expected.get(1).isNumber()))
                throw new IllegalStateException("BETWEEN 값 오류");
            Object input=facts.get(field);
            if(input==null) {missing.add(field);continue;}
            if(!(input instanceof String || input instanceof Boolean || input instanceof Number))
                throw new IllegalArgumentException("상황 값은 문자열·숫자·Boolean이어야 합니다: "+field);
            JsonNode actual=mapper.valueToTree(input);
            boolean matches=switch(op) {
                case "EQ" -> equal(actual,expected,field);
                case "IN" -> {
                    boolean found=false;
                    for(JsonNode item:expected) if(equal(actual,item,field)) found=true;
                    yield found;
                }
                case "BETWEEN" -> {
                    if(!actual.isIntegralNumber()) throw new IllegalArgumentException("정수 상황 값이 필요합니다: "+field);
                    yield actual.decimalValue().compareTo(expected.get(0).decimalValue())>=0 && actual.decimalValue().compareTo(expected.get(1).decimalValue())<=0;
                }
                default -> throw new IllegalStateException("지원하지 않는 연산자");
            };
            if(!matches)mismatch=true;
        }
        return mismatch?new Result(State.MISMATCH,List.of()):new Result(missing.isEmpty()?State.MATCH:State.UNKNOWN,List.copyOf(missing));
    }
    private boolean equal(JsonNode actual,JsonNode expected,String field) {
        if(actual.isNumber() && expected.isNumber()) return actual.decimalValue().compareTo(expected.decimalValue())==0;
        if(actual.getNodeType()!=expected.getNodeType())throw new IllegalArgumentException("상황 값 타입 불일치: "+field);
        return actual.equals(expected);
    }
}
