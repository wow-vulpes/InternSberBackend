package ru.kkd.domain.model;

public enum RuleCode {
    GOAL_NO_OWNER(Severity.CRITICAL),
    GOAL_NO_KR(Severity.CRITICAL),
    KR_WEIGHTS_SUM(Severity.MAJOR),
    KR_STALE_PROGRESS(Severity.MINOR);

    private final Severity severity;

    RuleCode(Severity severity) {
        this.severity = severity;
    }

    public Severity severity() {
        return severity;
    }
}
