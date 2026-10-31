package ru.kkd.api;

import java.util.List;
import java.util.Locale;
import ru.kkd.application.QualitySummary;
import ru.kkd.domain.model.IssueDetails;
import ru.kkd.domain.model.IssuePage;
import ru.kkd.domain.model.MetricCode;
import ru.kkd.domain.model.MetricResult;
import ru.kkd.domain.model.MetricTrend;
import ru.kkd.domain.model.RunDetails;
import ru.kkd.domain.model.RunResult;

final class ApiMapper {

    private ApiMapper() {}

    static ApiResponses.Run run(RunDetails run) {
        return new ApiResponses.Run(
                run.id(),
                run.snapshotDate(),
                lower(run.status()),
                run.comparedToId(),
                run.startedAt(),
                run.finishedAt(),
                run.issuesCount(),
                run.errorCode());
    }

    static ApiResponses.Summary summary(QualitySummary summary) {
        var quality = summary.quality();
        var counts = quality.issuesBySeverity();
        var previousCounts = quality.previousIssuesBySeverity();
        var delta = quality.delta();
        return new ApiResponses.Summary(
                ref(summary.run()),
                ref(summary.comparedTo()),
                new ApiResponses.Unit(
                        summary.unit().id(), summary.unit().name(), summary.unit().parentId()),
                quality.metrics().stream().map(ApiMapper::metric).toList(),
                List.of(
                        severityComparison(
                                "critical",
                                counts.critical(),
                                previousCounts == null ? null : previousCounts.critical()),
                        severityComparison(
                                "major",
                                counts.major(),
                                previousCounts == null ? null : previousCounts.major()),
                        severityComparison(
                                "minor",
                                counts.minor(),
                                previousCounts == null ? null : previousCounts.minor())),
                new ApiResponses.Delta(
                        delta.newCount(), delta.persistingCount(), delta.resolvedCount()));
    }

    static ApiResponses.Issues issues(IssuePage page) {
        return new ApiResponses.Issues(
                page.items().stream().map(ApiMapper::issue).toList(),
                page.total(),
                page.limit(),
                page.offset());
    }

    private static ApiResponses.RunRef ref(RunResult run) {
        return run == null ? null : new ApiResponses.RunRef(run.id(), run.snapshotDate());
    }

    private static ApiResponses.Metric metric(MetricResult metric) {
        return new ApiResponses.Metric(
                lower(metric.code()),
                title(metric.code()),
                metric.code() == MetricCode.CLEAN_GOALS_SHARE ? "%" : "шт.",
                metric.current(),
                metric.previous(),
                metric.delta(),
                trend(metric.trend()),
                lower(metric.severity()));
    }

    private static ApiResponses.SeverityComparison severityComparison(
            String code, long current, Long previous) {
        return new ApiResponses.SeverityComparison(
                code, current, previous, previous == null ? null : current - previous);
    }

    private static String trend(MetricTrend value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case IMPROVED -> "better";
            case DETERIORATED -> "worse";
            case UNCHANGED -> "unchanged";
        };
    }

    private static String title(MetricCode code) {
        return switch (code) {
            case CLEAN_GOALS_SHARE -> "Доля корректных целей";
            case AFFECTED_GOALS_COUNT -> "Цели с проблемами";
            case ISSUES_COUNT -> "Проблемы";
        };
    }

    private static ApiResponses.Issue issue(IssueDetails issue) {
        return new ApiResponses.Issue(
                issue.runId(),
                issue.ruleCode().name(),
                issue.ruleTitle(),
                lower(issue.severity()),
                lower(issue.objectType()),
                issue.objectId(),
                issue.objectTitle(),
                issue.goalId(),
                issue.goalTitle(),
                issue.unitId(),
                issue.unitName());
    }

    private static String lower(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }
}
