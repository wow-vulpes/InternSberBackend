package ru.kkd.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.kkd.domain.model.RuleCode;
import ru.kkd.support.PostgresTestSupport;

@Testcontainers
class DatabaseSchemaTest {

    @Container static final PostgreSQLContainer<?> POSTGRES = PostgresTestSupport.newContainer();

    private Connection connection;

    @BeforeAll
    static void migrate() throws Exception {
        PostgresTestSupport.migrate(POSTGRES);
    }

    @BeforeEach
    void begin() throws Exception {
        connection = PostgresTestSupport.connect(POSTGRES);
        connection.setAutoCommit(false);
    }

    @AfterEach
    void rollback() throws Exception {
        connection.rollback();
        connection.close();
    }

    @Test
    void ruleCatalogMatchesDomainMetadata() throws Exception {
        try (PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT code, severity, object_type FROM kkd.kkd_rule ORDER BY code");
                ResultSet rows = statement.executeQuery()) {
            int count = 0;
            while (rows.next()) {
                RuleCode rule = RuleCode.valueOf(rows.getString("code"));
                assertThat(rows.getString("severity")).isEqualTo(rule.severity().name());
                assertThat(rows.getString("object_type"))
                        .isEqualTo(rule == RuleCode.KR_STALE_PROGRESS ? "KEY_RESULT" : "GOAL");
                count++;
            }
            assertThat(count).isEqualTo(RuleCode.values().length);
        }
    }

    @Test
    void sourceConstraintsRejectBrokenReferencesAndBlankIds() throws Exception {
        rejects("23514", "INSERT INTO kkd.unit(id,name) VALUES ('  ','Blank')");
        rejects("23514", "INSERT INTO kkd.unit(id,name) VALUES (E'\\t','Blank')");
        rejects("23503", "INSERT INTO kkd.unit(id,name,parent_id) VALUES ('U1','Unit','missing')");
        execute("INSERT INTO kkd.unit(id,name) VALUES ('U1','Unit')");
        rejects("23514", "UPDATE kkd.unit SET parent_id=id WHERE id='U1'");
        rejects(
                "23503",
                "INSERT INTO kkd.goal(id,title,unit_id,status) VALUES ('G1','Goal','missing','ACTIVE')");
        rejects(
                "23514",
                "INSERT INTO kkd.goal(id,title,unit_id,status) VALUES ('G1','Goal','U1','OTHER')");
        execute("INSERT INTO kkd.goal(id,title,unit_id,status) VALUES ('G1','Goal','U1','ACTIVE')");
        rejects("23514", "UPDATE kkd.goal SET parent_goal_id=id WHERE id='G1'");
        rejects(
                "23503",
                "INSERT INTO kkd.key_result(id,title,goal_id,weight) VALUES ('K1','KR','missing',100)");
        rejects("23502", "INSERT INTO kkd.key_result(id,title,goal_id) VALUES ('K1','KR','G1')");
    }

    @Test
    void numericValuesRoundTripWithoutRoundingAndMissingQualityFieldsAreAllowed() throws Exception {
        execute("INSERT INTO kkd.unit(id,name) VALUES ('U1','Unit')");
        execute(
                "INSERT INTO kkd.goal(id,title,unit_id,status,owner_id) VALUES ('G1','Goal','U1','ACTIVE','   ')");
        BigDecimal weight = new BigDecimal("99.9999999999999999999900");
        execute(
                "INSERT INTO kkd.key_result(id,title,goal_id,weight) VALUES ('K1','KR','G1',?)",
                weight);
        try (PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT weight,target_value,actual_value,progress_updated_on FROM kkd.key_result WHERE id='K1'");
                ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getBigDecimal("weight")).isEqualTo(weight);
            assertThat(rows.getObject("target_value")).isNull();
            assertThat(rows.getObject("actual_value")).isNull();
            assertThat(rows.getObject("progress_updated_on")).isNull();
        }
        execute("UPDATE kkd.key_result SET weight=-1 WHERE id='K1'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "-Infinity"})
    void rejectsNonFiniteNumbers(String value) throws Exception {
        execute("INSERT INTO kkd.unit(id,name) VALUES ('U1','Unit')");
        execute("INSERT INTO kkd.goal(id,title,unit_id,status) VALUES ('G1','Goal','U1','ACTIVE')");
        rejects(
                "23514",
                "INSERT INTO kkd.key_result(id,title,goal_id,weight) VALUES ('K1','KR','G1',?::numeric)",
                value);
        run("R1");
        unit("R1", "U1", null);
        goal("R1", "G1", "U1", null);
        rejects(
                "23514",
                "INSERT INTO kkd.key_result_snapshot(run_id,id,title,goal_id,weight) VALUES ('R1','K1','KR','G1',?::numeric)",
                value);
    }

    @Test
    void snapshotReferencesStayWithinRunButIdsMayRepeatAcrossRuns() throws Exception {
        run("R1");
        run("R2");
        unit("R1", "U1", null);
        goal("R1", "G1", "U1", null);
        rejects(
                "23503",
                "INSERT INTO kkd.goal_snapshot(run_id,id,title,unit_id,status) VALUES ('R2','G1','Goal','U1','ACTIVE')");
        rejects(
                "23503",
                "INSERT INTO kkd.unit_snapshot(run_id,id,name,parent_id) VALUES ('R2','U2','Child','U1')");
        unit("R2", "U1", null);
        goal("R2", "G1", "U1", null);
        keyResult("R1", "K1", "G1");
        keyResult("R2", "K1", "G1");
        rejects(
                "23503",
                "INSERT INTO kkd.goal_snapshot(run_id,id,title,unit_id,status,parent_goal_id) VALUES ('R2','G2','Goal','U1','ACTIVE','missing')");
        rejects(
                "23505",
                "INSERT INTO kkd.unit_snapshot(run_id,id,name) VALUES ('R1','U1','Duplicate')");
    }

    @Test
    void issuesHaveUniqueIdentityAndConsistentRuleAndObjectLinks() throws Exception {
        fixture("R1");
        goal("R1", "G2", "U1", null);
        issue("R1", "GOAL_NO_OWNER", "CRITICAL", "G1", null);
        rejects(
                "23505",
                "INSERT INTO kkd.kkd_issue(run_id,rule_code,severity,goal_id) VALUES ('R1','GOAL_NO_OWNER','CRITICAL','G1')");
        rejects(
                "23503",
                "INSERT INTO kkd.kkd_issue(run_id,rule_code,severity,goal_id) VALUES ('R1','GOAL_NO_KR','MINOR','G1')");
        rejects(
                "23503",
                "INSERT INTO kkd.kkd_issue(run_id,rule_code,severity,goal_id,key_result_id) VALUES ('R1','KR_STALE_PROGRESS','MINOR','G2','K1')");
        rejects(
                "23503",
                "INSERT INTO kkd.kkd_issue(run_id,rule_code,severity,goal_id) VALUES ('R1','KR_STALE_PROGRESS','MINOR','G1')");
        issue("R1", "KR_STALE_PROGRESS", "MINOR", "G1", "K1");
        assertThat(
                        strings(
                                "SELECT object_type || ':' || object_id FROM kkd.kkd_issue WHERE run_id='R1' ORDER BY object_type"))
                .containsExactly("GOAL:G1", "KEY_RESULT:K1");
    }

    @Test
    void rejectsInvalidRunCreationAndComparison() throws Exception {
        rejects(
                "23514",
                "INSERT INTO kkd.kkd_run(id,snapshot_date,status,finished_at) VALUES ('R0',DATE '2026-10-26','COMPLETED',CURRENT_TIMESTAMP)");
        rejects(
                "23503",
                "INSERT INTO kkd.kkd_run(id,snapshot_date,compared_to_id) VALUES ('R0',DATE '2026-10-26','missing')");
        run("R1");
        rejects(
                "23514",
                "INSERT INTO kkd.kkd_run(id,snapshot_date,compared_to_id) VALUES ('R2',DATE '2026-10-26','R1')");
        complete("R1");
        rejects(
                "23514",
                "INSERT INTO kkd.kkd_run(id,snapshot_date,compared_to_id) VALUES ('R2',DATE '2026-10-19','R1')");
        execute(
                "INSERT INTO kkd.kkd_run(id,snapshot_date,compared_to_id) VALUES ('R2',DATE '2026-10-26','R1')");
        rejects("55000", "UPDATE kkd.kkd_run SET snapshot_date=DATE '2026-10-27' WHERE id='R2'");
        rejects(
                "23514",
                "UPDATE kkd.kkd_run SET status='FAILED',finished_at=CURRENT_TIMESTAMP WHERE id='R2'");
        execute(
                "UPDATE kkd.kkd_run SET status='FAILED',finished_at=CURRENT_TIMESTAMP,error_code='TEST' WHERE id='R2'");
        rejects(
                "23514",
                "INSERT INTO kkd.kkd_run(id,snapshot_date,compared_to_id) VALUES ('R3',DATE '2026-10-26','R2')");
    }

    @ParameterizedTest
    @ValueSource(strings = {"unit", "goal"})
    void cyclicSnapshotCannotBeCompleted(String hierarchy) throws Exception {
        run("R1");
        unit("R1", "U1", null);
        if (hierarchy.equals("unit")) {
            unit("R1", "U2", "U1");
            unit("R1", "U3", "U2");
            execute("UPDATE kkd.unit_snapshot SET parent_id='U3' WHERE run_id='R1' AND id='U1'");
            assertThat(strings("SELECT unit_id FROM kkd.unit_subtree('R1','U1') ORDER BY unit_id"))
                    .containsExactly("U1", "U2", "U3");
        } else {
            goal("R1", "G1", "U1", null);
            goal("R1", "G2", "U1", "G1");
            goal("R1", "G3", "U1", "G2");
            execute(
                    "UPDATE kkd.goal_snapshot SET parent_goal_id='G3' WHERE run_id='R1' AND id='G1'");
        }
        rejects(
                "23514",
                "UPDATE kkd.kkd_run SET status='COMPLETED',finished_at=CURRENT_TIMESTAMP WHERE id='R1'");
        assertThat(strings("SELECT status FROM kkd.kkd_run WHERE id='R1'"))
                .containsExactly("RUNNING");
    }

    @Test
    void subtreeUsesSnapshotAndIncludesAllDescendantsOnlyOnce() throws Exception {
        run("R1");
        unit("R1", "A", null);
        unit("R1", "B", "A");
        unit("R1", "C", "A");
        unit("R1", "D", "B");
        unit("R1", "X", null);
        complete("R1");
        run("R2");
        unit("R2", "A", null);
        unit("R2", "X", "A");
        assertThat(strings("SELECT unit_id FROM kkd.unit_subtree('R1','A') ORDER BY unit_id"))
                .containsExactly("A", "B", "C", "D");
        assertThat(strings("SELECT unit_id FROM kkd.unit_subtree('R1','B') ORDER BY unit_id"))
                .containsExactly("B", "D");
        assertThat(strings("SELECT unit_id FROM kkd.unit_subtree('R1','missing')")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"COMPLETED", "FAILED"})
    void terminalRunAndItsDataCannotBeChanged(String status) throws Exception {
        fixture("R1");
        issue("R1", "GOAL_NO_OWNER", "CRITICAL", "G1", null);
        if (status.equals("COMPLETED")) {
            complete("R1");
        } else {
            execute(
                    "UPDATE kkd.kkd_run SET status='FAILED',finished_at=CURRENT_TIMESTAMP,error_code='TEST' WHERE id='R1'");
        }
        rejects("55000", "DELETE FROM kkd.kkd_run WHERE id='R1'");
        rejects(
                "55000",
                "UPDATE kkd.kkd_run SET status='RUNNING',finished_at=NULL,error_code=NULL WHERE id='R1'");
        for (String table :
                List.of("unit_snapshot", "goal_snapshot", "key_result_snapshot", "kkd_issue")) {
            rejects("55000", "UPDATE kkd." + table + " SET run_id=run_id WHERE run_id='R1'");
            rejects("55000", "DELETE FROM kkd." + table + " WHERE run_id='R1'");
        }
        rejects("55000", "INSERT INTO kkd.unit_snapshot(run_id,id,name) VALUES ('R1','U2','Unit')");
        rejects(
                "55000",
                "INSERT INTO kkd.goal_snapshot(run_id,id,title,unit_id,status) VALUES ('R1','G2','Goal','U1','ACTIVE')");
        rejects(
                "55000",
                "INSERT INTO kkd.key_result_snapshot(run_id,id,title,goal_id,weight) VALUES ('R1','K2','KR','G1',100)");
        rejects(
                "55000",
                "INSERT INTO kkd.kkd_issue(run_id,rule_code,severity,goal_id) VALUES ('R1','GOAL_NO_KR','CRITICAL','G1')");
    }

    @Test
    void runningSnapshotCannotMoveToAnotherRun() throws Exception {
        run("R1");
        run("R2");
        unit("R1", "U1", null);
        rejects("55000", "UPDATE kkd.unit_snapshot SET run_id='R2' WHERE run_id='R1'");
    }

    @Test
    void changingAndDeletingSourceDoesNotChangeHistory() throws Exception {
        execute("INSERT INTO kkd.unit(id,name) VALUES ('U1','Original')");
        run("R1");
        execute(
                "INSERT INTO kkd.unit_snapshot(run_id,id,name,parent_id) SELECT 'R1',id,name,parent_id FROM kkd.unit");
        complete("R1");
        execute("UPDATE kkd.unit SET name='Changed' WHERE id='U1'");
        execute("DELETE FROM kkd.unit WHERE id='U1'");
        assertThat(strings("SELECT name FROM kkd.unit_snapshot WHERE run_id='R1' AND id='U1'"))
                .containsExactly("Original");
    }

    @Test
    void viewHidesPartialCountsAndSeparatesSeverity() throws Exception {
        fixture("R1");
        issue("R1", "GOAL_NO_OWNER", "CRITICAL", "G1", null);
        issue("R1", "KR_WEIGHTS_SUM", "MAJOR", "G1", null);
        issue("R1", "KR_STALE_PROGRESS", "MINOR", "G1", "K1");
        assertThat(strings("SELECT issues_count::text FROM kkd.run_statistics WHERE run_id='R1'"))
                .containsExactly((String) null);
        complete("R1");
        assertThat(
                        strings(
                                "SELECT issues_count || ':' || critical_count || ':' || major_count || ':' || minor_count FROM kkd.run_statistics WHERE run_id='R1'"))
                .containsExactly("3:1:1:1");
        run("R2");
        complete("R2");
        assertThat(strings("SELECT issues_count::text FROM kkd.run_statistics WHERE run_id='R2'"))
                .containsExactly("0");
        run("R3");
        execute(
                "UPDATE kkd.kkd_run SET status='FAILED',finished_at=CURRENT_TIMESTAMP,error_code='TEST' WHERE id='R3'");
        assertThat(strings("SELECT issues_count::text FROM kkd.run_statistics WHERE run_id='R3'"))
                .containsExactly((String) null);
    }

    private void fixture(String runId) throws Exception {
        run(runId);
        unit(runId, "U1", null);
        goal(runId, "G1", "U1", null);
        keyResult(runId, "K1", "G1");
    }

    private void run(String id) throws Exception {
        execute("INSERT INTO kkd.kkd_run(id,snapshot_date) VALUES (?,DATE '2026-10-26')", id);
    }

    private void complete(String id) throws Exception {
        execute(
                "UPDATE kkd.kkd_run SET status='COMPLETED',finished_at=CURRENT_TIMESTAMP WHERE id=?",
                id);
    }

    private void unit(String runId, String id, String parent) throws Exception {
        execute(
                "INSERT INTO kkd.unit_snapshot(run_id,id,name,parent_id) VALUES (?,?,?,?)",
                runId,
                id,
                id,
                parent);
    }

    private void goal(String runId, String id, String unit, String parent) throws Exception {
        execute(
                "INSERT INTO kkd.goal_snapshot(run_id,id,title,unit_id,parent_goal_id,status) VALUES (?,?,?,?,?,'ACTIVE')",
                runId,
                id,
                id,
                unit,
                parent);
    }

    private void keyResult(String runId, String id, String goal) throws Exception {
        execute(
                "INSERT INTO kkd.key_result_snapshot(run_id,id,title,goal_id,weight) VALUES (?,?,?,?,100)",
                runId,
                id,
                id,
                goal);
    }

    private void issue(String run, String rule, String severity, String goal, String keyResult)
            throws Exception {
        execute(
                "INSERT INTO kkd.kkd_issue(run_id,rule_code,severity,goal_id,key_result_id) VALUES (?,?,?,?,?)",
                run,
                rule,
                severity,
                goal,
                keyResult);
    }

    private void execute(String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.execute();
        }
    }

    private void rejects(String state, String sql, Object... parameters) throws Exception {
        Savepoint point = connection.setSavepoint();
        try {
            SQLException error = assertThrows(SQLException.class, () -> execute(sql, parameters));
            assertThat(error.getSQLState()).isEqualTo(state);
        } finally {
            connection.rollback(point);
            connection.releaseSavepoint(point);
        }
    }

    private List<String> strings(String sql) throws SQLException {
        List<String> result = new ArrayList<>();
        try (var statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                result.add(rows.getString(1));
            }
        }
        return result;
    }
}
