package ru.kkd.domain.model;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record QualitySnapshot(Set<String> activeGoalIds, List<SnapshotIssue> issues) {

    public QualitySnapshot {
        ModelValidation.requireNonNull(activeGoalIds, "Active goal ids");
        ModelValidation.requireNonNull(issues, "Issues");
        for (String goalId : activeGoalIds) {
            ModelValidation.requireId(goalId, "Goal id");
        }
        Set<IssueIdentity> identities = new HashSet<>();
        for (SnapshotIssue issue : issues) {
            ModelValidation.requireNonNull(issue, "Issue");
            if (!activeGoalIds.contains(issue.goalId())) {
                throw new IllegalArgumentException(
                        "Issue must belong to an active goal in the scope");
            }
            if (!identities.add(issue.identity())) {
                throw new IllegalArgumentException("Issue identities must be unique");
            }
        }
        activeGoalIds = Set.copyOf(activeGoalIds);
        issues = List.copyOf(issues);
    }
}
