package ru.kkd.domain.metric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import ru.kkd.domain.model.IssueDelta;
import ru.kkd.domain.model.IssueIdentity;
import ru.kkd.domain.model.MetricCode;
import ru.kkd.domain.model.MetricResult;
import ru.kkd.domain.model.MetricTrend;
import ru.kkd.domain.model.ObjectType;
import ru.kkd.domain.model.QualityComparison;
import ru.kkd.domain.model.QualitySnapshot;
import ru.kkd.domain.model.RuleCode;
import ru.kkd.domain.model.Severity;
import ru.kkd.domain.model.SeverityCounts;
import ru.kkd.domain.model.SnapshotIssue;

class QualityMetricsCalculatorTest {

    private final QualityMetricsCalculator calculator = new QualityMetricsCalculator();

    @Test
    void keyResultIssuesAffectGoalOnceButEveryIssueIsCounted() {
        QualitySnapshot current =
                new QualitySnapshot(
                        Set.of("G1", "G2", "G3"),
                        List.of(
                                issue(RuleCode.KR_WEIGHTS_SUM, "G1", "G1"),
                                issue(RuleCode.KR_STALE_PROGRESS, "K1", "G1"),
                                issue(RuleCode.KR_STALE_PROGRESS, "K2", "G1")));
        QualityComparison result = calculator.calculate(current, null);
        assertValue(result, MetricCode.CLEAN_GOALS_SHARE, "66.7", null, null, null);
        assertValue(result, MetricCode.AFFECTED_GOALS_COUNT, "1", null, null, null);
        assertValue(result, MetricCode.ISSUES_COUNT, "3", null, null, null);
        assertThat(result.issuesBySeverity()).isEqualTo(new SeverityCounts(0, 1, 2));
        assertThat(result.metrics())
                .extracting(MetricResult::severity)
                .containsOnly(Severity.MAJOR);
    }

    @ParameterizedTest
    @CsvSource({"16,1,6.3", "3,2,66.7", "3,1,33.3", "7,7,100.0", "7,0,0.0"})
    void percentageUsesHalfUpRoundingToOneDecimal(int total, int clean, String expected) {
        QualityComparison result = calculator.calculate(snapshot(total, clean), null);
        assertValue(result, MetricCode.CLEAN_GOALS_SHARE, expected, null, null, null);
        assertThat(metric(result, MetricCode.CLEAN_GOALS_SHARE).current().scale()).isEqualTo(1);
    }

    @Test
    void percentageDeltaSubtractsDisplayedValuesInPercentagePoints() {
        QualityComparison result = calculator.calculate(snapshot(3, 1), snapshot(6, 1));
        assertValue(
                result, MetricCode.CLEAN_GOALS_SHARE, "33.3", "16.7", "16.6", MetricTrend.IMPROVED);
        assertValue(result, MetricCode.AFFECTED_GOALS_COUNT, "2", "5", "-3", MetricTrend.IMPROVED);
        assertValue(result, MetricCode.ISSUES_COUNT, "2", "5", "-3", MetricTrend.IMPROVED);
    }

    @Test
    void deteriorationHasOppositeNumericDirectionForShareAndCounts() {
        QualityComparison result = calculator.calculate(snapshot(6, 1), snapshot(3, 1));
        assertValue(
                result,
                MetricCode.CLEAN_GOALS_SHARE,
                "16.7",
                "33.3",
                "-16.6",
                MetricTrend.DETERIORATED);
        assertValue(
                result, MetricCode.AFFECTED_GOALS_COUNT, "5", "2", "3", MetricTrend.DETERIORATED);
        assertValue(result, MetricCode.ISSUES_COUNT, "5", "2", "3", MetricTrend.DETERIORATED);
    }

    @Test
    void noComparisonKeepsAllPreviousValuesAndChangeCountersNull() {
        QualityComparison result = calculator.calculate(snapshot(3, 2), null);
        assertThat(result.delta()).isEqualTo(new IssueDelta(null, null, null));
        for (MetricResult metric : result.metrics()) {
            assertThat(metric.previous()).isNull();
            assertThat(metric.delta()).isNull();
            assertThat(metric.trend()).isNull();
        }
    }

    @Test
    void emptyFirstSnapshotHasUndefinedShareButZeroCurrentCounts() {
        QualityComparison result = calculator.calculate(snapshot(0, 0), null);
        assertValue(result, MetricCode.CLEAN_GOALS_SHARE, null, null, null, null);
        assertValue(result, MetricCode.AFFECTED_GOALS_COUNT, "0", null, null, null);
        assertValue(result, MetricCode.ISSUES_COUNT, "0", null, null, null);
        assertThat(result.delta()).isEqualTo(new IssueDelta(null, null, null));
        assertThat(result.issuesBySeverity()).isEqualTo(new SeverityCounts(0, 0, 0));
        assertThat(result.metrics()).extracting(MetricResult::severity).containsOnlyNulls();
    }

    @Test
    void twoEmptySnapshotsHaveComparableZeroCountsAndUndefinedShare() {
        QualityComparison result = calculator.calculate(snapshot(0, 0), snapshot(0, 0));
        assertValue(result, MetricCode.CLEAN_GOALS_SHARE, null, null, null, null);
        assertValue(result, MetricCode.AFFECTED_GOALS_COUNT, "0", "0", "0", MetricTrend.UNCHANGED);
        assertValue(result, MetricCode.ISSUES_COUNT, "0", "0", "0", MetricTrend.UNCHANGED);
        assertThat(result.delta()).isEqualTo(new IssueDelta(0L, 0L, 0L));
    }

    @Test
    void emptyPreviousSnapshotDoesNotFabricatePreviousPercentage() {
        QualityComparison result = calculator.calculate(snapshot(3, 2), snapshot(0, 0));
        assertValue(result, MetricCode.CLEAN_GOALS_SHARE, "66.7", null, null, null);
        assertValue(
                result, MetricCode.AFFECTED_GOALS_COUNT, "1", "0", "1", MetricTrend.DETERIORATED);
        assertThat(result.delta()).isEqualTo(new IssueDelta(1L, 0L, 0L));
    }

    @Test
    void noActiveGoalsNowResolvesOldScopeIssuesButLeavesShareUndefined() {
        QualityComparison result = calculator.calculate(snapshot(0, 0), snapshot(3, 2));
        assertValue(result, MetricCode.CLEAN_GOALS_SHARE, null, "66.7", null, null);
        assertValue(result, MetricCode.AFFECTED_GOALS_COUNT, "0", "1", "-1", MetricTrend.IMPROVED);
        assertThat(result.delta()).isEqualTo(new IssueDelta(0L, 0L, 1L));
    }

    @Test
    void unchangedValuesAndRepeatedCallsProduceSameImmutableResult() {
        QualitySnapshot snapshot = snapshot(3, 2);
        QualityComparison first = calculator.calculate(snapshot, snapshot);
        assertThat(first.metrics())
                .extracting(MetricResult::trend)
                .containsOnly(MetricTrend.UNCHANGED);
        assertThat(first.delta()).isEqualTo(new IssueDelta(0L, 1L, 0L));
        assertThat(calculator.calculate(snapshot, snapshot)).isEqualTo(first);
        assertThatThrownBy(() -> first.metrics().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void roundingCanMakeDisplayedShareUnchangedWithoutHidingCountChange() {
        QualityComparison result =
                calculator.calculate(snapshot(10_000, 9_999), snapshot(10_000, 9_998));
        assertValue(
                result,
                MetricCode.CLEAN_GOALS_SHARE,
                "100.0",
                "100.0",
                "0.0",
                MetricTrend.UNCHANGED);
        assertValue(result, MetricCode.AFFECTED_GOALS_COUNT, "1", "2", "-1", MetricTrend.IMPROVED);
    }

    @Test
    void deltaMatchesRuleAndObjectIdentityInsteadOfOnlyGoal() {
        QualitySnapshot previous =
                new QualitySnapshot(
                        Set.of("G1", "G2"),
                        List.of(
                                issue(RuleCode.GOAL_NO_OWNER, "G1", "G1"),
                                issue(RuleCode.KR_STALE_PROGRESS, "K1", "G1"),
                                issue(RuleCode.KR_WEIGHTS_SUM, "G2", "G2")));
        QualitySnapshot current =
                new QualitySnapshot(
                        Set.of("G1", "G2"),
                        List.of(
                                issue(RuleCode.GOAL_NO_KR, "G1", "G1"),
                                issue(RuleCode.KR_STALE_PROGRESS, "K2", "G1"),
                                issue(RuleCode.KR_WEIGHTS_SUM, "G2", "G2")));
        assertThat(calculator.calculate(current, previous).delta())
                .isEqualTo(new IssueDelta(2L, 1L, 2L));
    }

    @Test
    void sameKeyResultIdentityPersistsWhenItsGoalChanges() {
        QualitySnapshot previous =
                new QualitySnapshot(
                        Set.of("OLD"), List.of(issue(RuleCode.KR_STALE_PROGRESS, "K1", "OLD")));
        QualitySnapshot current =
                new QualitySnapshot(
                        Set.of("NEW"), List.of(issue(RuleCode.KR_STALE_PROGRESS, "K1", "NEW")));
        assertThat(calculator.calculate(current, previous).delta())
                .isEqualTo(new IssueDelta(0L, 1L, 0L));
    }

    @ParameterizedTest
    @EnumSource(Severity.class)
    void metricSeverityComesFromStoredCurrentProblems(Severity severity) {
        QualitySnapshot current =
                new QualitySnapshot(
                        Set.of("G1"),
                        List.of(
                                new SnapshotIssue(
                                        new IssueIdentity(
                                                RuleCode.GOAL_NO_OWNER, ObjectType.GOAL, "G1"),
                                        "G1",
                                        severity)));
        QualityComparison result = calculator.calculate(current, snapshot(3, 0));
        assertThat(result.metrics()).extracting(MetricResult::severity).containsOnly(severity);
        long total =
                result.issuesBySeverity().critical()
                        + result.issuesBySeverity().major()
                        + result.issuesBySeverity().minor();
        assertThat(total).isEqualTo(1);
    }

    @Test
    void currentSeverityIgnoresResolvedCriticalProblems() {
        QualitySnapshot previous =
                new QualitySnapshot(
                        Set.of("G1"), List.of(issue(RuleCode.GOAL_NO_OWNER, "G1", "G1")));
        QualitySnapshot current =
                new QualitySnapshot(
                        Set.of("G1"), List.of(issue(RuleCode.KR_STALE_PROGRESS, "K1", "G1")));
        assertThat(calculator.calculate(current, previous).metrics())
                .extracting(MetricResult::severity)
                .containsOnly(Severity.MINOR);
        assertThat(calculator.calculate(snapshot(1, 1), previous).metrics())
                .extracting(MetricResult::severity)
                .containsOnlyNulls();
    }

    @Test
    void snapshotDefensivelyCopiesInputsAndCalculatorDoesNotModifyThem() {
        Set<String> goals = new HashSet<>(Set.of("G1"));
        List<SnapshotIssue> issues =
                new ArrayList<>(List.of(issue(RuleCode.GOAL_NO_OWNER, "G1", "G1")));
        QualitySnapshot snapshot = new QualitySnapshot(goals, issues);
        goals.clear();
        issues.clear();
        calculator.calculate(snapshot, snapshot);
        assertThat(snapshot.activeGoalIds()).containsExactly("G1");
        assertThat(snapshot.issues()).hasSize(1);
        assertThatThrownBy(() -> snapshot.activeGoalIds().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.issues().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void inconsistentSnapshotIsRejectedBeforeCalculation() {
        SnapshotIssue issue = issue(RuleCode.GOAL_NO_OWNER, "G1", "G1");
        assertThatThrownBy(() -> new QualitySnapshot(Set.of(), List.of(issue)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QualitySnapshot(Set.of("G1"), List.of(issue, issue)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QualitySnapshot(null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QualitySnapshot(Set.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private QualitySnapshot snapshot(int total, int clean) {
        Set<String> goals =
                IntStream.range(0, total).mapToObj(i -> "G" + i).collect(Collectors.toSet());
        List<SnapshotIssue> issues =
                IntStream.range(clean, total)
                        .mapToObj(i -> issue(RuleCode.GOAL_NO_OWNER, "G" + i, "G" + i))
                        .toList();
        return new QualitySnapshot(goals, issues);
    }

    private SnapshotIssue issue(RuleCode rule, String objectId, String goalId) {
        ObjectType type =
                rule == RuleCode.KR_STALE_PROGRESS ? ObjectType.KEY_RESULT : ObjectType.GOAL;
        return new SnapshotIssue(new IssueIdentity(rule, type, objectId), goalId, rule.severity());
    }

    private MetricResult metric(QualityComparison result, MetricCode code) {
        return result.metrics().stream()
                .filter(metric -> metric.code() == code)
                .findFirst()
                .orElseThrow();
    }

    private void assertValue(
            QualityComparison result,
            MetricCode code,
            String current,
            String previous,
            String delta,
            MetricTrend trend) {
        MetricResult metric = metric(result, code);
        assertDecimal(metric.current(), current);
        assertDecimal(metric.previous(), previous);
        assertDecimal(metric.delta(), delta);
        assertThat(metric.trend()).isEqualTo(trend);
    }

    private void assertDecimal(BigDecimal actual, String expected) {
        if (expected == null) {
            assertThat(actual).isNull();
        } else {
            assertThat(actual).isEqualByComparingTo(expected);
        }
    }
}
