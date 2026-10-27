package com.example.wonway.policy.persistence;

import jakarta.persistence.*;

@Entity(name = "V4PolicyProjectRuleEntity")
@Table(name = "kb_project_rule")
public class PolicyProjectRuleEntity {
    protected PolicyProjectRuleEntity() {}
    @Id
    @Column(name = "rule_code", nullable = false, length = 100)
    private String ruleCode;
    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    public PolicyProjectRuleEntity(String ruleCode, String payloadJson) {
        this.ruleCode = ruleCode;
        this.payloadJson = payloadJson;
    }
    public String getRuleCode() { return ruleCode; }
    public String getPayloadJson() { return payloadJson; }
}
