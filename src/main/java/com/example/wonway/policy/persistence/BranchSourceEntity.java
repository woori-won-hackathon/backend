package com.example.wonway.policy.persistence;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;

@Entity(name = "V4BranchSourceEntity")
@Table(name = "kb_branch_source")
@IdClass(BranchSourceEntity.Key.class)
public class BranchSourceEntity {
    @Id @Column(name="branch_code", length=120) private String branchCode;
    @Id @Column(name="source_id", length=100) private String sourceId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name="branch_code", insertable=false, updatable=false)
    private PolicyBranchEntity branch;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name="source_id", insertable=false, updatable=false)
    private PolicySourceEntity source;
    protected BranchSourceEntity() {}
    public BranchSourceEntity(String branchCode, String sourceId) {
        this.branchCode=branchCode; this.sourceId=sourceId;
    }
    public static class Key implements Serializable {
        public String branchCode;
        public String sourceId;
        public Key() {}
        @Override public boolean equals(Object other) {
            return other instanceof Key k && Objects.equals(branchCode,k.branchCode) && Objects.equals(sourceId,k.sourceId);
        }
        @Override public int hashCode() { return Objects.hash(branchCode,sourceId); }
    }
}
