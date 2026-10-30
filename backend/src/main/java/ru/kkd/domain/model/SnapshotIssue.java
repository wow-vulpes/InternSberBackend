package ru.kkd.domain.model;

public record SnapshotIssue(IssueIdentity identity, String goalId, Severity severity) {

    public SnapshotIssue {
        ModelValidation.requireNonNull(identity, "Issue identity");
        ModelValidation.requireId(goalId, "Goal id");
        ModelValidation.requireNonNull(severity, "Severity");
    }
}
