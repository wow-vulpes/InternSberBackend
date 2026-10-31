package ru.kkd.domain.model;

import java.util.List;

public record QualityComparison(
        List<MetricResult> metrics,
        SeverityCounts issuesBySeverity,
        SeverityCounts previousIssuesBySeverity,
        IssueDelta delta) {

    public QualityComparison {
        metrics = List.copyOf(metrics);
    }
}
