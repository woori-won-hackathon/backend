package com.example.wonway.policy.persistence;

import jakarta.persistence.*;

@Entity(name = "V4PolicySourceEntity")
@Table(name = "kb_source")
public class PolicySourceEntity {
    protected PolicySourceEntity() {}
    @Id
    @Column(name = "source_id", nullable = false, length = 100)
    private String sourceId;
    @Column(name = "title", nullable = false, length = 500)
    private String title;
    @Column(name = "source_url", nullable = true, columnDefinition = "TEXT")
    private String sourceUrl;
    @Column(name = "evidence_status", nullable = false, length = 60)
    private String evidenceStatus;
    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    public PolicySourceEntity(String sourceId, String title, String sourceUrl, String evidenceStatus, String payloadJson) {
        this.sourceId = sourceId;
        this.title = title;
        this.sourceUrl = sourceUrl;
        this.evidenceStatus = evidenceStatus;
        this.payloadJson = payloadJson;
    }
    public String getSourceId() { return sourceId; }
    public String getTitle() { return title; }
    public String getSourceUrl() { return sourceUrl; }
    public String getEvidenceStatus() { return evidenceStatus; }
    public String getPayloadJson() { return payloadJson; }
}
