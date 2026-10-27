package com.example.wonway.policy.persistence;

import jakarta.persistence.*;

@Entity(name = "V4ConsultationPolicyEntity")
@Table(name = "kb_policy")
public class ConsultationPolicyEntity {
    protected ConsultationPolicyEntity() {}
    @Id
    @Column(name = "policy_code", nullable = false, length = 100)
    private String policyCode;
    @Column(name = "intent", nullable = false, length = 100)
    private String intent;
    @Column(name = "title", nullable = false, length = 500)
    private String title;
    @Column(name = "approval_status", nullable = false, length = 40)
    private String approvalStatus;
    @Column(name = "effective_from", nullable = true)
    private java.time.LocalDate effectiveFrom;
    @Column(name = "effective_to", nullable = true)
    private java.time.LocalDate effectiveTo;
    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    public ConsultationPolicyEntity(String policyCode, String intent, String title, String approvalStatus, java.time.LocalDate effectiveFrom, java.time.LocalDate effectiveTo, String payloadJson) {
        this.policyCode = policyCode;
        this.intent = intent;
        this.title = title;
        this.approvalStatus = approvalStatus;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.payloadJson = payloadJson;
    }
    public String getPolicyCode() { return policyCode; }
    public String getIntent() { return intent; }
    public String getTitle() { return title; }
    public String getApprovalStatus() { return approvalStatus; }
    public java.time.LocalDate getEffectiveFrom() { return effectiveFrom; }
    public java.time.LocalDate getEffectiveTo() { return effectiveTo; }
    public String getPayloadJson() { return payloadJson; }
}
