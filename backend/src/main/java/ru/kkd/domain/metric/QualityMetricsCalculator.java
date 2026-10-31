package ru.kkd.domain.metric;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import ru.kkd.domain.model.IssueDelta;
import ru.kkd.domain.model.IssueIdentity;
import ru.kkd.domain.model.MetricCode;
import ru.kkd.domain.model.MetricResult;
import ru.kkd.domain.model.MetricTrend;
import ru.kkd.domain.model.QualityComparison;
import ru.kkd.domain.model.QualitySnapshot;
import ru.kkd.domain.model.Severity;
import ru.kkd.domain.model.SeverityCounts;
import ru.kkd.domain.model.SnapshotIssue;

public final class QualityMetricsCalculator {

    public QualityComparison calculate(QualitySnapshot current, QualitySnapshot previous) {
        if (current == null) {
            throw new IllegalArgumentException("Current snapshot must not be null");
        }
        long affected = affectedGoals(current);
        long previousAffected = previous == null ? 0 : affectedGoals(previous);
        SeverityCounts severityCounts = countSeverity(current);
        Severity severity = highestSeverity(severityCounts);
        return new QualityComparison(
                List.of(
                        compare(
                                MetricCode.CLEAN_GOALS_SHARE,
                                cleanShare(current, affected),
                                previous == null ? null : cleanShare(previous, previousAffected),
                                severity),
                        compare(
                                MetricCode.AFFECTED_GOALS_COUNT,
                                BigDecimal.valueOf(affected),
                                previous == null ? null : BigDecimal.valueOf(previousAffected),
                                severity),
                        compare(
                                MetricCode.ISSUES_COUNT,
                                BigDecimal.valueOf(current.issues().size()),
                                previous == null
                                        ? null
                                        : BigDecimal.valueOf(previous.issues().size()),
                                severity)),
                severityCounts,
                previous == null ? null : countSeverity(previous),
                issueDelta(current, previous));
    }

    private long affectedGoals(QualitySnapshot snapshot) {
        return snapshot.issues().stream().map(SnapshotIssue::goalId).distinct().count();
    }

    private BigDecimal cleanShare(QualitySnapshot snapshot, long affected) {
        long total = snapshot.activeGoalIds().size();
        if (total == 0) {
            return null;
        }
        return BigDecimal.valueOf(total - affected)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }

    private MetricResult compare(
            MetricCode code, BigDecimal current, BigDecimal previous, Severity severity) {
        BigDecimal delta = current == null || previous == null ? null : current.subtract(previous);
        MetricTrend trend = null;
        if (delta != null) {
            if (delta.signum() == 0) {
                trend = MetricTrend.UNCHANGED;
            } else {
                boolean improved = (delta.signum() > 0) == code.increaseIsBetter();
                trend = improved ? MetricTrend.IMPROVED : MetricTrend.DETERIORATED;
            }
        }
        return new MetricResult(code, current, previous, delta, trend, severity);
    }

    private SeverityCounts countSeverity(QualitySnapshot snapshot) {
        long critical = 0;
        long major = 0;
        long minor = 0;
        for (SnapshotIssue issue : snapshot.issues()) {
            switch (issue.severity()) {
                case CRITICAL -> critical++;
                case MAJOR -> major++;
                case MINOR -> minor++;
            }
        }
        return new SeverityCounts(critical, major, minor);
    }

    private Severity highestSeverity(SeverityCounts counts) {
        if (counts.critical() > 0) {
            return Severity.CRITICAL;
        }
        if (counts.major() > 0) {
            return Severity.MAJOR;
        }
        return counts.minor() > 0 ? Severity.MINOR : null;
    }

    private IssueDelta issueDelta(QualitySnapshot current, QualitySnapshot previous) {
        if (previous == null) {
            return new IssueDelta(null, null, null);
        }
        Set<IssueIdentity> currentIds = identities(current);
        Set<IssueIdentity> previousIds = identities(previous);
        Set<IssueIdentity> persisting = new HashSet<>(currentIds);
        persisting.retainAll(previousIds);
        long persistingCount = persisting.size();
        return new IssueDelta(
                currentIds.size() - persistingCount,
                persistingCount,
                previousIds.size() - persistingCount);
    }

    private Set<IssueIdentity> identities(QualitySnapshot snapshot) {
        return snapshot.issues().stream().map(SnapshotIssue::identity).collect(Collectors.toSet());
    }
}
