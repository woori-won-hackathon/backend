package com.example.wonway.policy.persistence;

import jakarta.persistence.*;

@Entity(name = "V4PolicyBranchEntity")
@Table(name = "kb_branch")
public class PolicyBranchEntity {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_code", insertable = false, updatable = false)
    private ConsultationPolicyEntity policy;
    protected PolicyBranchEntity() {}
    @Id
    @Column(name = "branch_code", nullable = false, length = 120)
    private String branchCode;
    @Column(name = "policy_code", nullable = false, length = 100)
    private String policyCode;
    @Column(name = "conditions_json", nullable = false, columnDefinition = "TEXT")
    private String conditionsJson;
    @Column(name = "assessment_json", nullable = false, columnDefinition = "TEXT")
    private String assessmentJson;
    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    public PolicyBranchEntity(String branchCode, String policyCode, String conditionsJson, String assessmentJson, String payloadJson) {
        this.branchCode = branchCode;
        this.policyCode = policyCode;
        this.conditionsJson = conditionsJson;
        this.assessmentJson = assessmentJson;
        this.payloadJson = payloadJson;
    }
    public String getBranchCode() { return branchCode; }
    public String getPolicyCode() { return policyCode; }
    public String getConditionsJson() { return conditionsJson; }
    public String getAssessmentJson() { return assessmentJson; }
    public String getPayloadJson() { return payloadJson; }
}
