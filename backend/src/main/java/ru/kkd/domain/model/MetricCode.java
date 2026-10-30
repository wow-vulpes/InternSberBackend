package ru.kkd.domain.model;

public enum MetricCode {
    CLEAN_GOALS_SHARE(true),
    AFFECTED_GOALS_COUNT(false),
    ISSUES_COUNT(false);

    private final boolean increaseIsBetter;

    MetricCode(boolean increaseIsBetter) {
        this.increaseIsBetter = increaseIsBetter;
    }

    public boolean increaseIsBetter() {
        return increaseIsBetter;
    }
}
