package com.example.wonway.policy.model;

import com.example.wonway.common.contract.PolicyResult;
import java.util.List;

public record PolicyEvaluation(Status status,List<Match> matches,List<String> missingFields,String message) {
    public enum Status { MATCHED, NEEDS_INFORMATION, REVIEW_REQUIRED, UNSUPPORTED }
    public record Match(PolicyResult policy,List<Branch> branches,String evidenceLevel) {}
    public record Branch(String branchCode,String applicability,List<String> missingFields,
                         List<String> officialSteps,List<String> restrictions,List<String> remainingChecks,
                         List<String> sourceUrls,String evidenceStatus,String contactOrganization) {}
    public List<PolicyResult> policies() { return matches.stream().map(Match::policy).toList(); }
}
