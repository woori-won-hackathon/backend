package com.example.wonway.policy.persistence;

import jakarta.persistence.*;

@Entity(name = "V4PolicyDatasetEntity")
@Table(name = "kb_dataset")
public class PolicyDatasetEntity {
    protected PolicyDatasetEntity() {}
    @Id
    @Column(name = "dataset_key", nullable = false, length = 40)
    private String datasetKey;
    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;
    @Column(name = "checksum", nullable = false, length = 64)
    private String checksum;
    @Column(name = "metadata_json", nullable = false, columnDefinition = "TEXT")
    private String metadataJson;

    public PolicyDatasetEntity(String datasetKey, int schemaVersion, String checksum, String metadataJson) {
        this.datasetKey = datasetKey;
        this.schemaVersion = schemaVersion;
        this.checksum = checksum;
        this.metadataJson = metadataJson;
    }
    public String getDatasetKey() { return datasetKey; }
    public int getSchemaVersion() { return schemaVersion; }
    public String getChecksum() { return checksum; }
    public String getMetadataJson() { return metadataJson; }
}
