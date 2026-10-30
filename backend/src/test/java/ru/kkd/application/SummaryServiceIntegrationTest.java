package ru.kkd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.kkd.domain.model.IssueDelta;
import ru.kkd.domain.model.MetricCode;
import ru.kkd.domain.model.MetricResult;
import ru.kkd.domain.model.MetricTrend;
import ru.kkd.domain.model.RunStatus;
import ru.kkd.domain.model.Severity;
import ru.kkd.domain.model.SeverityCounts;
import ru.kkd.support.PostgresTestSupport;

@SpringBootTest(properties = {"kkd.demo.enabled=false", "kkd.run.enabled=false"})
@Testcontainers
class SummaryServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            PostgresTestSupport.newContainer().withUsername("kkd");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired SummaryService summaries;
    @Autowired RunService runs;
    @Autowired DemoDataInitializer demo;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clearDatabase() {
        jdbc.execute("TRUNCATE kkd.kkd_run,kkd.unit,kkd.goal,kkd.key_result CASCADE");
    }

    @Test
    void firstDemoRunHasCurrentMetricsButNoFabricatedComparison() {
        demo.initialize();
        QualitySummary result = summaries.summary(DemoDataInitializer.FIRST_RUN_ID, "U1");
        assertThat(result.comparedTo()).isNull();
        assertThat(result.previousUnit()).isNull();
        assertMetric(result, MetricCode.CLEAN_GOALS_SHARE, "65.2", null, null, null);
        assertMetric(result, MetricCode.AFFECTED_GOALS_COUNT, "8", null, null, null);
        assertMetric(result, MetricCode.ISSUES_COUNT, "10", null, null, null);
        assertThat(result.quality().delta()).isEqualTo(new IssueDelta(null, null, null));
        assertThat(result.quality().issuesBySeverity()).isEqualTo(new SeverityCounts(5, 2, 3));
    }

    @Test
    void secondDemoRunHasExpectedMetricsSeverityAndChangeCategories() {
        demo.initialize();
        QualitySummary result = summaries.summary(DemoDataInitializer.SECOND_RUN_ID, "U1");
        assertThat(result.comparedTo().id()).isEqualTo(DemoDataInitializer.FIRST_RUN_ID);
        assertThat(result.unit().name()).isEqualTo("Банк");
        assertThat(result.previousUnit()).isEqualTo(result.unit());
        assertThat(result.quality().metrics())
                .extracting(MetricResult::code)
                .containsExactly(
                        MetricCode.CLEAN_GOALS_SHARE,
                        MetricCode.AFFECTED_GOALS_COUNT,
                        MetricCode.ISSUES_COUNT);
        assertMetric(
                result, MetricCode.CLEAN_GOALS_SHARE, "65.2", "65.2", "0.0", MetricTrend.UNCHANGED);
        assertMetric(result, MetricCode.AFFECTED_GOALS_COUNT, "8", "8", "0", MetricTrend.UNCHANGED);
        assertMetric(result, MetricCode.ISSUES_COUNT, "8", "10", "-2", MetricTrend.IMPROVED);
        assertThat(result.quality().issuesBySeverity()).isEqualTo(new SeverityCounts(3, 2, 3));
        assertThat(result.quality().metrics())
                .extracting(MetricResult::severity)
                .containsOnly(Severity.CRITICAL);
        assertThat(result.quality().delta()).isEqualTo(new IssueDelta(3L, 5L, 5L));
    }

    @Test
    void selectedUnitIncludesDescendantsOnceAndExcludesInactiveGoals() {
        demo.initialize();
        QualitySummary result = summaries.summary(DemoDataInitializer.SECOND_RUN_ID, "U2");
        assertMetric(
                result,
                MetricCode.CLEAN_GOALS_SHARE,
                "80.0",
                "86.7",
                "-6.7",
                MetricTrend.DETERIORATED);
        assertMetric(
                result, MetricCode.AFFECTED_GOALS_COUNT, "3", "2", "1", MetricTrend.DETERIORATED);
        assertMetric(result, MetricCode.ISSUES_COUNT, "3", "4", "-1", MetricTrend.IMPROVED);
        assertThat(result.quality().issuesBySeverity()).isEqualTo(new SeverityCounts(1, 0, 2));
        assertThat(result.quality().delta()).isEqualTo(new IssueDelta(1L, 2L, 2L));
        QualitySummary leaf = summaries.summary(DemoDataInitializer.SECOND_RUN_ID, "U3");
        assertMetric(
                leaf, MetricCode.CLEAN_GOALS_SHARE, "100.0", "100.0", "0.0", MetricTrend.UNCHANGED);
        assertMetric(leaf, MetricCode.ISSUES_COUNT, "0", "0", "0", MetricTrend.UNCHANGED);
        assertThat(leaf.quality().metrics()).extracting(MetricResult::severity).containsOnlyNulls();
    }

    @Test
    void historyIgnoresCurrentSourcesAndLaterRuns() {
        demo.initialize();
        QualitySummary expected = summaries.summary(DemoDataInitializer.SECOND_RUN_ID, "U2");
        jdbc.update("UPDATE kkd.unit SET parent_id=NULL,name='Renamed now' WHERE id='U3'");
        jdbc.update("DELETE FROM kkd.key_result");
        jdbc.update("DELETE FROM kkd.goal");
        jdbc.update("DELETE FROM kkd.unit");
        var later = runs.run(LocalDate.of(2026, 10, 27));
        assertThat(later.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(summaries.summary(DemoDataInitializer.SECOND_RUN_ID, "U2")).isEqualTo(expected);
    }

    @Test
    void eachComparisonUsesHierarchyOfItsOwnSnapshot() {
        jdbc.update("INSERT INTO kkd.unit(id,name) VALUES ('ROOT','Root')");
        jdbc.update(
                "INSERT INTO kkd.unit(id,name,parent_id) VALUES ('A','Old A','ROOT'),('X','X','ROOT')");
        jdbc.update("INSERT INTO kkd.unit(id,name,parent_id) VALUES ('B','B','A')");
        jdbc.update(
                "INSERT INTO kkd.goal(id,title,unit_id,status) VALUES ('G1','One','B','ACTIVE')");
        jdbc.update(
                "INSERT INTO kkd.goal(id,title,unit_id,owner_id,status) VALUES ('G2','Two','X','owner','ACTIVE')");
        var first = runs.run(LocalDate.of(2026, 10, 19));
        jdbc.update("UPDATE kkd.unit SET parent_id='X' WHERE id='B'");
        jdbc.update("UPDATE kkd.unit SET name='New A' WHERE id='A'");
        jdbc.update(
                "INSERT INTO kkd.goal(id,title,unit_id,owner_id,status) VALUES ('G3','Three','A','owner','ACTIVE')");
        var second = runs.run(LocalDate.of(2026, 10, 26));
        QualitySummary selected = summaries.summary(second.id(), "A");
        assertThat(selected.comparedTo().id()).isEqualTo(first.id());
        assertThat(selected.unit().name()).isEqualTo("New A");
        assertThat(selected.previousUnit().name()).isEqualTo("Old A");
        assertMetric(selected, MetricCode.ISSUES_COUNT, "1", "2", "-1", MetricTrend.IMPROVED);
        assertMetric(
                selected, MetricCode.AFFECTED_GOALS_COUNT, "1", "1", "0", MetricTrend.UNCHANGED);
        assertThat(selected.quality().delta()).isEqualTo(new IssueDelta(1L, 0L, 2L));
        QualitySummary all = summaries.summary(second.id(), "ROOT");
        assertThat(all.quality().delta()).isEqualTo(new IssueDelta(1L, 3L, 0L));
        assertMetric(all, MetricCode.ISSUES_COUNT, "4", "3", "1", MetricTrend.DETERIORATED);
    }

    @Test
    void newUnitHasEmptyPreviousScopeWhileComparisonRunRemainsPresent() {
        jdbc.update("INSERT INTO kkd.unit(id,name) VALUES ('ROOT','Root')");
        var first = runs.run(LocalDate.of(2026, 10, 19));
        jdbc.update("INSERT INTO kkd.unit(id,name,parent_id) VALUES ('NEW','New','ROOT')");
        jdbc.update(
                "INSERT INTO kkd.goal(id,title,unit_id,status) VALUES ('G1','Goal','NEW','ACTIVE')");
        var second = runs.run(LocalDate.of(2026, 10, 26));
        QualitySummary result = summaries.summary(second.id(), "NEW");
        assertThat(result.comparedTo().id()).isEqualTo(first.id());
        assertThat(result.previousUnit()).isNull();
        assertMetric(result, MetricCode.CLEAN_GOALS_SHARE, "0.0", null, null, null);
        assertMetric(
                result, MetricCode.AFFECTED_GOALS_COUNT, "1", "0", "1", MetricTrend.DETERIORATED);
        assertMetric(result, MetricCode.ISSUES_COUNT, "2", "0", "2", MetricTrend.DETERIORATED);
        assertThat(result.quality().delta()).isEqualTo(new IssueDelta(2L, 0L, 0L));
    }

    @Test
    void movedGoalIsResolvedInOldScopeNewInDestinationAndPersistingAtCommonRoot() {
        jdbc.update("INSERT INTO kkd.unit(id,name) VALUES ('ROOT','Root')");
        jdbc.update(
                "INSERT INTO kkd.unit(id,name,parent_id) VALUES ('A','A','ROOT'),('B','B','ROOT')");
        jdbc.update(
                "INSERT INTO kkd.goal(id,title,unit_id,status) VALUES ('G1','Goal','A','ACTIVE')");
        runs.run(LocalDate.of(2026, 10, 19));
        jdbc.update("UPDATE kkd.goal SET unit_id='B' WHERE id='G1'");
        var second = runs.run(LocalDate.of(2026, 10, 26));
        assertThat(summaries.summary(second.id(), "A").quality().delta())
                .isEqualTo(new IssueDelta(0L, 0L, 2L));
        assertThat(summaries.summary(second.id(), "B").quality().delta())
                .isEqualTo(new IssueDelta(2L, 0L, 0L));
        assertThat(summaries.summary(second.id(), "ROOT").quality().delta())
                .isEqualTo(new IssueDelta(0L, 2L, 0L));
    }

    @Test
    void emptyUnitDoesNotHaveAZeroPercentQualityValue() {
        jdbc.update("INSERT INTO kkd.unit(id,name) VALUES ('ROOT','Root')");
        var first = runs.run(LocalDate.of(2026, 10, 19));
        QualitySummary noComparison = summaries.summary(first.id(), "ROOT");
        assertMetric(noComparison, MetricCode.CLEAN_GOALS_SHARE, null, null, null, null);
        assertMetric(noComparison, MetricCode.ISSUES_COUNT, "0", null, null, null);
        var second = runs.run(LocalDate.of(2026, 10, 26));
        QualitySummary comparison = summaries.summary(second.id(), "ROOT");
        assertMetric(comparison, MetricCode.CLEAN_GOALS_SHARE, null, null, null, null);
        assertMetric(comparison, MetricCode.ISSUES_COUNT, "0", "0", "0", MetricTrend.UNCHANGED);
        assertThat(comparison.quality().delta()).isEqualTo(new IssueDelta(0L, 0L, 0L));
    }

    @Test
    void deletedAndDeactivatedGoalsLeaveScopeAndResolveTheirIssues() {
        jdbc.update("INSERT INTO kkd.unit(id,name) VALUES ('ROOT','Root')");
        jdbc.update(
                "INSERT INTO kkd.goal(id,title,unit_id,status) VALUES ('G1','One','ROOT','ACTIVE'),('G2','Two','ROOT','ACTIVE')");
        runs.run(LocalDate.of(2026, 10, 19));
        jdbc.update("UPDATE kkd.goal SET status='INACTIVE' WHERE id='G1'");
        jdbc.update("DELETE FROM kkd.goal WHERE id='G2'");
        var second = runs.run(LocalDate.of(2026, 10, 26));
        QualitySummary result = summaries.summary(second.id(), "ROOT");
        assertMetric(result, MetricCode.CLEAN_GOALS_SHARE, null, "0.0", null, null);
        assertMetric(result, MetricCode.AFFECTED_GOALS_COUNT, "0", "2", "-2", MetricTrend.IMPROVED);
        assertMetric(result, MetricCode.ISSUES_COUNT, "0", "4", "-4", MetricTrend.IMPROVED);
        assertThat(result.quality().delta()).isEqualTo(new IssueDelta(0L, 0L, 4L));
        assertThat(result.quality().metrics())
                .extracting(MetricResult::severity)
                .containsOnlyNulls();
    }

    @Test
    void replacementOfKeyResultDoesNotLookLikePersistingIssueOnSameGoal() {
        jdbc.update("INSERT INTO kkd.unit(id,name) VALUES ('ROOT','Root')");
        jdbc.update(
                "INSERT INTO kkd.goal(id,title,unit_id,owner_id,status) VALUES ('G1','Goal','ROOT','owner','ACTIVE')");
        jdbc.update(
                "INSERT INTO kkd.key_result(id,title,goal_id,weight) VALUES ('OLD','KR','G1',100)");
        runs.run(LocalDate.of(2026, 10, 19));
        jdbc.update("UPDATE kkd.key_result SET id='NEW' WHERE id='OLD'");
        var second = runs.run(LocalDate.of(2026, 10, 26));
        QualitySummary result = summaries.summary(second.id(), "ROOT");
        assertMetric(result, MetricCode.ISSUES_COUNT, "1", "1", "0", MetricTrend.UNCHANGED);
        assertThat(result.quality().delta()).isEqualTo(new IssueDelta(1L, 0L, 1L));
        assertThat(result.quality().issuesBySeverity()).isEqualTo(new SeverityCounts(0, 0, 1));
        assertThat(result.quality().metrics())
                .extracting(MetricResult::severity)
                .containsOnly(Severity.MINOR);
    }

    @Test
    void unknownRunAndUnitHaveExplicitLookupFailures() {
        assertThatThrownBy(() -> summaries.summary("unknown", "ROOT"))
                .isInstanceOf(ResourceNotFoundException.class)
                .satisfies(
                        error ->
                                assertThat(((ResourceNotFoundException) error).resource())
                                        .isEqualTo("Run"));
        jdbc.update("INSERT INTO kkd.unit(id,name) VALUES ('ROOT','Root')");
        var run = runs.run(LocalDate.of(2026, 10, 19));
        assertThatThrownBy(() -> summaries.summary(run.id(), "unknown"))
                .isInstanceOf(ResourceNotFoundException.class)
                .satisfies(
                        error ->
                                assertThat(((ResourceNotFoundException) error).resource())
                                        .isEqualTo("Unit"));
    }

    @ParameterizedTest
    @EnumSource(
            value = RunStatus.class,
            names = {"RUNNING", "FAILED"})
    void partialRunsAreNotReportedAsCompletedMetrics(RunStatus status) {
        jdbc.update(
                "INSERT INTO kkd.kkd_run(id,snapshot_date) VALUES ('PARTIAL',DATE '2026-10-26')");
        if (status == RunStatus.FAILED) {
            jdbc.update(
                    "UPDATE kkd.kkd_run SET status='FAILED',finished_at=clock_timestamp(),error_code='TEST_FAILURE' WHERE id='PARTIAL'");
        }
        assertThatThrownBy(() -> summaries.summary("PARTIAL", "ROOT"))
                .isInstanceOf(RunNotCompletedException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void invalidIdsAreRejected(String invalid) {
        assertThatThrownBy(() -> summaries.summary(invalid, "ROOT"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> summaries.summary("RUN", invalid))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertMetric(
            QualitySummary result,
            MetricCode code,
            String current,
            String previous,
            String delta,
            MetricTrend trend) {
        MetricResult metric =
                result.quality().metrics().stream()
                        .filter(item -> item.code() == code)
                        .findFirst()
                        .orElseThrow();
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
