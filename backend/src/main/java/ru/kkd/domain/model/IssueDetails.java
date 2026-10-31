package ru.kkd.domain.model;

public record IssueDetails(
        String runId,
        RuleCode ruleCode,
        String ruleTitle,
        Severity severity,
        ObjectType objectType,
        String objectId,
        String objectTitle,
        String goalId,
        String goalTitle,
        String unitId,
        String unitName) {}
