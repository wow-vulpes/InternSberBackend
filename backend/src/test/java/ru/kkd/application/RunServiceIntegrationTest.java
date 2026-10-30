package ru.kkd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.kkd.domain.model.RunResult;
import ru.kkd.domain.model.RunStatus;
import ru.kkd.support.PostgresTestSupport;

@SpringBootTest(properties = {"kkd.demo.enabled=false", "kkd.run.enabled=false"})
@Testcontainers
class RunServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            PostgresTestSupport.newContainer().withUsername("kkd");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired RunService service;
    @Autowired DemoDataInitializer demo;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @BeforeEach
    void clearDatabase() {
        // Administrative cleanup is limited to this disposable database, not normal application
        // DML.
        jdbc.execute("TRUNCATE kkd.kkd_run,kkd.unit,kkd.goal,kkd.key_result CASCADE");
    }

    @Test
    void demoUsesRealRulesAndContainsAllChangeCategories() {
        var results = demo.initialize();
        String first = DemoDataInitializer.FIRST_RUN_ID;
        String second = DemoDataInitializer.SECOND_RUN_ID;
        assertThat(results).extracting(RunResult::status).containsOnly(RunStatus.COMPLETED);
        assertThat(results.get(0).comparedToId()).isNull();
        assertThat(results.get(1).comparedToId()).isEqualTo(first);
        assertThat(results).extracting(RunResult::issuesCount).containsExactly(10L, 8L);
        assertThat(count("kkd.unit")).isEqualTo(3);
        assertThat(count("kkd.goal")).isEqualTo(24);
        assertThat(count("kkd.key_result")).isEqualTo(23);
        assertThat(count("kkd.unit_snapshot")).isEqualTo(6);
        assertThat(count("kkd.goal_snapshot")).isEqualTo(48);
        assertThat(count("kkd.key_result_snapshot")).isEqualTo(45);
        assertThat(count("kkd.kkd_run")).isEqualTo(2);
        assertThat(issues(first))
                .containsExactlyInAnyOrder(
                        "GOAL_NO_OWNER:GOAL:G01",
                        "GOAL_NO_KR:GOAL:G02",
                        "KR_WEIGHTS_SUM:GOAL:G03",
                        "KR_STALE_PROGRESS:KEY_RESULT:KR04",
                        "GOAL_NO_OWNER:GOAL:G05",
                        "KR_STALE_PROGRESS:KEY_RESULT:KR08",
                        "GOAL_NO_OWNER:GOAL:G09",
                        "KR_WEIGHTS_SUM:GOAL:G09",
                        "KR_STALE_PROGRESS:KEY_RESULT:KR09",
                        "GOAL_NO_KR:GOAL:G10");
        assertThat(issues(second))
                .containsExactlyInAnyOrder(
                        "KR_WEIGHTS_SUM:GOAL:G03",
                        "KR_STALE_PROGRESS:KEY_RESULT:KR04",
                        "GOAL_NO_OWNER:GOAL:G05",
                        "GOAL_NO_OWNER:GOAL:G06",
                        "KR_WEIGHTS_SUM:GOAL:G07",
                        "KR_STALE_PROGRESS:KEY_RESULT:KR09",
                        "GOAL_NO_KR:GOAL:G10",
                        "KR_STALE_PROGRESS:KEY_RESULT:KR11");
        Set<String> persisting = issues(first);
        persisting.retainAll(issues(second));
        Set<String> added = issues(second);
        added.removeAll(issues(first));
        Set<String> resolved = issues(first);
        resolved.removeAll(issues(second));
        // Fixture verification only: the metrics/delta feature is a separate implementation step.
        assertThat(persisting).hasSize(5);
        assertThat(added).hasSize(3);
        assertThat(resolved).hasSize(5);
        assertThat(
                        jdbc.queryForList(
                                "SELECT unit_id FROM kkd.unit_subtree(?,'U1')",
                                String.class,
                                second))
                .containsExactlyInAnyOrder("U1", "U2", "U3");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM kkd.kkd_issue WHERE goal_id='G24'",
                                Long.class))
                .isZero();
    }

    @Test
    void demoRestartDoesNotOverwriteSourcesOrAddRuns() {
        var original = demo.initialize();
        jdbc.update("UPDATE kkd.goal SET title='Изменено пользователем' WHERE id='G01'");
        RunResult third = service.run(LocalDate.of(2026, 10, 27));
        assertThat(demo.initialize()).isEqualTo(original);
        assertThat(count("kkd.kkd_run")).isEqualTo(3);
        assertThat(third.comparedToId()).isEqualTo(DemoDataInitializer.SECOND_RUN_ID);
        assertThat(jdbc.queryForObject("SELECT title FROM kkd.goal WHERE id='G01'", String.class))
                .isEqualTo("Изменено пользователем");
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "run", "partial-demo"})
    void demoRefusesExistingDataWithoutChanges(String state) {
        if (state.equals("source")) {
            jdbc.update("INSERT INTO kkd.unit(id,name) VALUES ('EXISTING','Existing')");
        } else {
            String id = state.equals("run") ? "EXISTING" : DemoDataInitializer.FIRST_RUN_ID;
            jdbc.update(
                    "INSERT INTO kkd.kkd_run(id,snapshot_date) VALUES (?,DATE '2026-10-19')", id);
        }
        assertThatThrownBy(demo::initialize).isInstanceOf(IllegalStateException.class);
        assertThat(count("kkd.unit")).isEqualTo(state.equals("source") ? 1 : 0);
        assertThat(count("kkd.kkd_run")).isEqualTo(state.equals("source") ? 0 : 1);
        assertThat(count("kkd.goal")).isZero();
    }

    @Test
    void emptySourceCompletesAndEachNormalInvocationCreatesNewRun() {
        var first = service.run(LocalDate.of(2026, 10, 26));
        var second = service.run(LocalDate.of(2026, 10, 26));
        assertThat(first.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(first.issuesCount()).isZero();
        assertThat(first.comparedToId()).isNull();
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.comparedToId()).isEqualTo(first.id());
        assertThat(count("kkd.kkd_run")).isEqualTo(2);
    }

    @Test
    void comparisonUsesLastCompletedEligibleRunAndSkipsNewerSnapshotDates() {
        var first = service.run(LocalDate.of(2026, 10, 1));
        var future = service.run(LocalDate.of(2026, 10, 4));
        var backdated = service.run(LocalDate.of(2026, 10, 2));
        var next = service.run(LocalDate.of(2026, 10, 5));
        assertThat(future.comparedToId()).isEqualTo(first.id());
        assertThat(backdated.comparedToId()).isEqualTo(first.id());
        assertThat(next.comparedToId()).isEqualTo(backdated.id());
    }

    @ParameterizedTest
    @ValueSource(strings = {"unit", "goal"})
    void cyclicSourceLeavesFailedRunWithoutPartialSnapshotAndNextRunSkipsIt(String type) {
        var previous = service.run(LocalDate.of(2026, 10, 19));
        jdbc.update("INSERT INTO kkd.unit(id,name) VALUES ('U1','One'),('U2','Two')");
        if (type.equals("unit")) {
            jdbc.update("UPDATE kkd.unit SET parent_id=CASE id WHEN 'U1' THEN 'U2' ELSE 'U1' END");
        } else {
            jdbc.update(
                    "INSERT INTO kkd.goal(id,title,unit_id,status) VALUES ('G1','One','U1','ACTIVE'),('G2','Two','U2','ACTIVE')");
            jdbc.update(
                    "UPDATE kkd.goal SET parent_goal_id=CASE id WHEN 'G1' THEN 'G2' ELSE 'G1' END");
        }
        var failed = service.run(LocalDate.of(2026, 10, 26));
        assertFailedWithoutSnapshot(failed);
        assertThat(failed.comparedToId()).isEqualTo(previous.id());
        jdbc.update(
                type.equals("unit")
                        ? "UPDATE kkd.unit SET parent_id=NULL"
                        : "UPDATE kkd.goal SET parent_goal_id=NULL");
        var recovered = service.run(LocalDate.of(2026, 10, 26));
        assertThat(recovered.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(recovered.comparedToId()).isEqualTo(previous.id());
    }

    @Test
    void issuePersistenceFailureRollsBackAllRowsAndDemoBootstrapIsAtomic() {
        jdbc.update("DELETE FROM kkd.kkd_rule WHERE code='KR_STALE_PROGRESS'");
        try {
            assertThatThrownBy(demo::initialize).isInstanceOf(IllegalStateException.class);
            assertThat(count("kkd.kkd_run")).isZero();
            assertThat(count("kkd.unit")).isZero();
            assertThat(count("kkd.goal")).isZero();
            assertThat(count("kkd.key_result")).isZero();
            jdbc.update("INSERT INTO kkd.unit(id,name) VALUES ('U1','Unit')");
            jdbc.update(
                    "INSERT INTO kkd.goal(id,title,unit_id,status) VALUES ('G1','Goal','U1','ACTIVE')");
            jdbc.update(
                    "INSERT INTO kkd.key_result(id,title,goal_id,weight) VALUES ('KR1','KR','G1',90)");
            assertFailedWithoutSnapshot(service.run(LocalDate.of(2026, 10, 26)));
        } finally {
            jdbc.update(
                    "INSERT INTO kkd.kkd_rule(code,title,severity,object_type) VALUES ('KR_STALE_PROGRESS','Устаревший факт КР','MINOR','KEY_RESULT')");
        }
    }

    @Test
    void sourceChangesDoNotRewriteCompletedSnapshots() {
        demo.initialize();
        jdbc.update(
                "UPDATE kkd.goal SET owner_id='changed',status='INACTIVE',unit_id='U3' WHERE id='G03'");
        jdbc.update("UPDATE kkd.key_result SET weight=100 WHERE id='KR03'");
        var next = service.run(LocalDate.of(2026, 10, 26));
        assertThat(issues(next.id())).doesNotContain("KR_WEIGHTS_SUM:GOAL:G03");
        assertThat(issues(DemoDataInitializer.SECOND_RUN_ID)).contains("KR_WEIGHTS_SUM:GOAL:G03");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT unit_id FROM kkd.goal_snapshot WHERE run_id=? AND id='G03'",
                                String.class,
                                DemoDataInitializer.SECOND_RUN_ID))
                .isEqualTo("U1");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT weight FROM kkd.key_result_snapshot WHERE run_id=? AND id='KR03'",
                                java.math.BigDecimal.class,
                                DemoDataInitializer.SECOND_RUN_ID))
                .isEqualByComparingTo("90");
    }

    @Test
    void snapshotAndRuleEvaluationPreserveNumericPrecision() {
        jdbc.update("INSERT INTO kkd.unit(id,name) VALUES ('U1','Unit')");
        jdbc.update(
                "INSERT INTO kkd.goal(id,title,unit_id,owner_id,status) VALUES ('G1','Goal','U1','owner','ACTIVE')");
        jdbc.update(
                "INSERT INTO kkd.key_result(id,title,goal_id,weight,progress_updated_on) VALUES ('KR1','KR','G1',99.999999999999999999999999,DATE '2026-10-26')");
        RunResult result = service.run(LocalDate.of(2026, 10, 26));
        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(issues(result.id())).containsExactly("KR_WEIGHTS_SUM:GOAL:G1");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT weight FROM kkd.key_result_snapshot WHERE run_id=?",
                                java.math.BigDecimal.class,
                                result.id()))
                .isEqualByComparingTo("99.999999999999999999999999");
    }

    @Test
    void secondDemoRunFailureRollsBackFirstRunAndAllSourceChanges() {
        jdbc.execute(
                """
                CREATE FUNCTION public.reject_second_demo() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.id='demo-2026-10-26' AND NEW.status='COMPLETED' THEN
                        RAISE EXCEPTION 'Injected second-run failure' USING ERRCODE='23514';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute(
                "CREATE TRIGGER reject_second_demo AFTER UPDATE ON kkd.kkd_run FOR EACH ROW EXECUTE FUNCTION public.reject_second_demo()");
        try {
            assertThatThrownBy(demo::initialize).isInstanceOf(IllegalStateException.class);
            assertThat(count("kkd.kkd_run")).isZero();
            assertThat(count("kkd.unit")).isZero();
            assertThat(count("kkd.goal")).isZero();
            assertThat(count("kkd.key_result")).isZero();
            assertThat(count("kkd.kkd_issue")).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER reject_second_demo ON kkd.kkd_run");
            jdbc.execute("DROP FUNCTION public.reject_second_demo()");
        }
        assertThat(demo.initialize()).hasSize(2);
    }

    @Test
    void concurrentDemoInitializationsReturnSamePair() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(demo::initialize);
            var second = executor.submit(demo::initialize);
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
            assertThat(count("kkd.kkd_run")).isEqualTo(2);
            assertThat(count("kkd.goal")).isEqualTo(24);
        }
    }

    @Test
    void nullDateDoesNotCreateRun() {
        assertThatThrownBy(() -> service.run(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("kkd.kkd_run")).isZero();
    }

    @Test
    void concurrentRunsWaitForExecutionLockAndFormOneComparisonChain() throws Exception {
        try (var blocker = dataSource.getConnection();
                var statement = blocker.createStatement();
                var executor = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            statement.execute("SELECT pg_advisory_xact_lock(757001,1)");
            var first = executor.submit(() -> service.run(LocalDate.of(2026, 10, 26)));
            var second = executor.submit(() -> service.run(LocalDate.of(2026, 10, 26)));
            try {
                awaitWaitingLocks("advisory", 2);
                assertThat(count("kkd.kkd_run")).isZero();
            } finally {
                blocker.commit();
            }
            RunResult one = first.get(10, TimeUnit.SECONDS);
            RunResult two = second.get(10, TimeUnit.SECONDS);
            assertThat(one.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(two.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(count("kkd.kkd_run")).isEqualTo(2);
            if (one.comparedToId() == null) {
                assertThat(two.comparedToId()).isEqualTo(one.id());
            } else {
                assertThat(two.comparedToId()).isNull();
                assertThat(one.comparedToId()).isEqualTo(two.id());
            }
        }
    }

    @Test
    void captureWaitsForSourceWriterAndCopiesItsCommittedState() throws Exception {
        jdbc.update("INSERT INTO kkd.unit(id,name) VALUES ('U1','Unit')");
        jdbc.update(
                "INSERT INTO kkd.goal(id,title,unit_id,status) VALUES ('G1','Goal','U1','ACTIVE')");
        try (var writer = dataSource.getConnection();
                var statement = writer.createStatement();
                var executor = Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);
            statement.execute("UPDATE kkd.goal SET owner_id='committed-owner' WHERE id='G1'");
            var run = executor.submit(() -> service.run(LocalDate.of(2026, 10, 26)));
            try {
                awaitWaitingLocks("relation", 1);
            } finally {
                writer.commit();
            }
            RunResult result = run.get(10, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(issues(result.id())).containsExactly("GOAL_NO_KR:GOAL:G1");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT owner_id FROM kkd.goal_snapshot WHERE run_id=?",
                                    String.class,
                                    result.id()))
                    .isEqualTo("committed-owner");
        }
    }

    private void awaitWaitingLocks(String type, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Long waiting =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM pg_locks WHERE locktype=? AND NOT granted",
                            Long.class,
                            type);
            if (waiting != null && waiting >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Expected waiting " + type + " locks: " + expected);
    }

    private void assertFailedWithoutSnapshot(RunResult result) {
        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.errorCode()).isEqualTo("RUN_EXECUTION_FAILED");
        assertThat(result.issuesCount()).isNull();
        for (String table :
                new String[] {
                    "unit_snapshot", "goal_snapshot", "key_result_snapshot", "kkd_issue"
                }) {
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM kkd." + table + " WHERE run_id=?",
                                    Long.class,
                                    result.id()))
                    .isZero();
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT finished_at IS NOT NULL FROM kkd.kkd_run WHERE id=?",
                                Boolean.class,
                                result.id()))
                .isTrue();
    }

    private Set<String> issues(String runId) {
        return new HashSet<>(
                jdbc.queryForList(
                        "SELECT rule_code||':'||object_type||':'||object_id FROM kkd.kkd_issue WHERE run_id=?",
                        String.class,
                        runId));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}
