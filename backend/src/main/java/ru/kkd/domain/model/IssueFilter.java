package ru.kkd.domain.model;

public record IssueFilter(
        String runId, String unitId, RuleCode rule, Severity severity, int limit, int offset) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    public IssueFilter {
        ModelValidation.requireId(runId, "Run id");
        if (unitId != null) {
            ModelValidation.requireId(unitId, "Unit id");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
    }
}
