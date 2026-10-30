package ru.kkd.domain.rule;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ru.kkd.domain.model.GoalSnapshot;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.KeyResultSnapshot;
import ru.kkd.domain.model.ObjectType;
import ru.kkd.domain.model.RuleCode;

public final class DataQualityChecker {

    private static final BigDecimal TOTAL_WEIGHT = new BigDecimal("100");
    private static final int STALE_PROGRESS_DAYS = 45;
    private static final Comparator<Issue> ISSUE_ORDER =
            Comparator.comparing(Issue::goalId)
                    .thenComparing(issue -> issue.ruleCode().name())
                    .thenComparing(issue -> issue.objectType().name())
                    .thenComparing(Issue::objectId);

    public List<Issue> check(List<GoalSnapshot> goals, LocalDate snapshotDate) {
        validateInput(goals, snapshotDate);

        List<Issue> issues = new ArrayList<>();
        for (GoalSnapshot goal : goals) {
            if (!goal.active()) {
                continue;
            }
            checkOwner(goal, issues);
            checkKeyResultPresence(goal, issues);
            checkWeights(goal, issues);
            checkProgress(goal, snapshotDate, issues);
        }
        issues.sort(ISSUE_ORDER);
        return List.copyOf(issues);
    }

    private void checkOwner(GoalSnapshot goal, List<Issue> issues) {
        if (goal.owner() == null || goal.owner().isBlank()) {
            addGoalIssue(RuleCode.GOAL_NO_OWNER, goal, issues);
        }
    }

    private void checkKeyResultPresence(GoalSnapshot goal, List<Issue> issues) {
        if (goal.keyResults().isEmpty()) {
            addGoalIssue(RuleCode.GOAL_NO_KR, goal, issues);
        }
    }

    private void checkWeights(GoalSnapshot goal, List<Issue> issues) {
        if (goal.keyResults().isEmpty()) {
            return;
        }
        BigDecimal total =
                goal.keyResults().stream()
                        .map(KeyResultSnapshot::weight)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(TOTAL_WEIGHT) != 0) {
            addGoalIssue(RuleCode.KR_WEIGHTS_SUM, goal, issues);
        }
    }

    private void checkProgress(GoalSnapshot goal, LocalDate snapshotDate, List<Issue> issues) {
        for (KeyResultSnapshot keyResult : goal.keyResults()) {
            LocalDate updatedOn = keyResult.progressUpdatedOn();
            if (updatedOn == null
                    || ChronoUnit.DAYS.between(updatedOn, snapshotDate) > STALE_PROGRESS_DAYS) {
                issues.add(
                        new Issue(
                                RuleCode.KR_STALE_PROGRESS,
                                ObjectType.KEY_RESULT,
                                keyResult.id(),
                                goal.id(),
                                goal.unitId()));
            }
        }
    }

    private void addGoalIssue(RuleCode rule, GoalSnapshot goal, List<Issue> issues) {
        issues.add(new Issue(rule, ObjectType.GOAL, goal.id(), goal.id(), goal.unitId()));
    }

    private void validateInput(List<GoalSnapshot> goals, LocalDate snapshotDate) {
        if (goals == null) {
            throw new IllegalArgumentException("Goals must not be null");
        }
        if (snapshotDate == null) {
            throw new IllegalArgumentException("Snapshot date must not be null");
        }

        Set<String> goalIds = new HashSet<>();
        Set<String> keyResultIds = new HashSet<>();
        for (GoalSnapshot goal : goals) {
            if (goal == null) {
                throw new IllegalArgumentException("Goal must not be null");
            }
            if (!goalIds.add(goal.id())) {
                throw new IllegalArgumentException("Goal ids must be unique");
            }
            for (KeyResultSnapshot keyResult : goal.keyResults()) {
                if (!keyResultIds.add(keyResult.id())) {
                    throw new IllegalArgumentException(
                            "Key result ids must be unique across the input");
                }
            }
        }
    }
}
