package ru.kkd.persistence;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.kkd.domain.model.GoalSnapshot;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.KeyResultSnapshot;
import ru.kkd.domain.model.ObjectType;
import ru.kkd.domain.model.RunDetails;
import ru.kkd.domain.model.RunResult;
import ru.kkd.domain.model.RunStatus;

@Repository
public class RunRepository {

    private final JdbcTemplate jdbc;

    public RunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockExecution() {
        // Shared by normal runs and demo initialization; released by commit/rollback.
        jdbc.execute("SET LOCAL lock_timeout = '10s'");
        jdbc.execute("SET LOCAL statement_timeout = '60s'");
        jdbc.execute("SELECT pg_advisory_xact_lock(757001, 1)");
    }

    public String previousCompletedRun(LocalDate snapshotDate) {
        return jdbc
                .query(
                        """
                        SELECT id FROM kkd.kkd_run
                        WHERE status='COMPLETED' AND snapshot_date <= ?
                        ORDER BY finished_at DESC, id DESC LIMIT 1
                        """,
                        (rs, row) -> rs.getString("id"),
                        snapshotDate)
                .stream()
                .findFirst()
                .orElse(null);
    }

    public void createRun(String id, LocalDate snapshotDate, String comparedToId) {
        jdbc.update(
                "INSERT INTO kkd.kkd_run(id,snapshot_date,compared_to_id) VALUES (?,?,?)",
                id,
                snapshotDate,
                comparedToId);
    }

    public void captureSnapshot(String runId) {
        // READ COMMITTED plus SHARE locks keeps all three copies consistent with one source state.
        jdbc.execute("LOCK TABLE kkd.unit, kkd.goal, kkd.key_result IN SHARE MODE");
        jdbc.update(
                """
                INSERT INTO kkd.unit_snapshot(run_id,id,name,parent_id)
                SELECT ?,id,name,parent_id FROM kkd.unit
                """,
                runId);
        jdbc.update(
                """
                INSERT INTO kkd.goal_snapshot(run_id,id,title,unit_id,owner_id,parent_goal_id,status,deadline)
                SELECT ?,id,title,unit_id,owner_id,parent_goal_id,status,deadline FROM kkd.goal
                """,
                runId);
        jdbc.update(
                """
                INSERT INTO kkd.key_result_snapshot(run_id,id,title,goal_id,target_value,actual_value,weight,progress_updated_on)
                SELECT ?,id,title,goal_id,target_value,actual_value,weight,progress_updated_on FROM kkd.key_result
                """,
                runId);
        jdbc.queryForList("SELECT kkd.validate_snapshot(?)", runId);
    }

    public List<GoalSnapshot> loadGoals(String runId) {
        Map<String, List<KeyResultSnapshot>> keyResults = new HashMap<>();
        jdbc.query(
                "SELECT id,goal_id,weight,progress_updated_on FROM kkd.key_result_snapshot WHERE run_id=? ORDER BY id",
                rs -> {
                    keyResults
                            .computeIfAbsent(rs.getString("goal_id"), ignored -> new ArrayList<>())
                            .add(
                                    new KeyResultSnapshot(
                                            rs.getString("id"),
                                            rs.getBigDecimal("weight"),
                                            rs.getObject("progress_updated_on", LocalDate.class)));
                },
                runId);
        return jdbc.query(
                "SELECT id,unit_id,owner_id,status FROM kkd.goal_snapshot WHERE run_id=? ORDER BY id",
                (rs, row) ->
                        new GoalSnapshot(
                                rs.getString("id"),
                                rs.getString("unit_id"),
                                rs.getString("owner_id"),
                                "ACTIVE".equals(rs.getString("status")),
                                keyResults.getOrDefault(rs.getString("id"), List.of())),
                runId);
    }

    public void saveIssues(String runId, List<Issue> issues) {
        List<Object[]> parameters =
                issues.stream()
                        .map(
                                issue ->
                                        new Object[] {
                                            runId,
                                            issue.ruleCode().name(),
                                            issue.severity().name(),
                                            issue.goalId(),
                                            issue.objectType() == ObjectType.KEY_RESULT
                                                    ? issue.objectId()
                                                    : null
                                        })
                        .toList();
        jdbc.batchUpdate(
                "INSERT INTO kkd.kkd_issue(run_id,rule_code,severity,goal_id,key_result_id) VALUES (?,?,?,?,?)",
                parameters);
    }

    public void complete(String runId) {
        jdbc.update(
                "UPDATE kkd.kkd_run SET status='COMPLETED',finished_at=clock_timestamp() WHERE id=?",
                runId);
    }

    public void fail(String runId) {
        jdbc.update(
                "UPDATE kkd.kkd_run SET status='FAILED',finished_at=clock_timestamp(),error_code='RUN_EXECUTION_FAILED' WHERE id=?",
                runId);
    }

    public Optional<RunResult> findRun(String runId) {
        return jdbc
                .query(
                        """
                        SELECT r.id,r.snapshot_date,r.status,r.compared_to_id,r.error_code,s.issues_count
                        FROM kkd.kkd_run r JOIN kkd.run_statistics s ON s.run_id=r.id WHERE r.id=?
                        """,
                        (rs, row) ->
                                new RunResult(
                                        rs.getString("id"),
                                        rs.getObject("snapshot_date", LocalDate.class),
                                        RunStatus.valueOf(rs.getString("status")),
                                        rs.getString("compared_to_id"),
                                        rs.getObject("issues_count", Long.class),
                                        rs.getString("error_code")),
                        runId)
                .stream()
                .findFirst();
    }

    public List<RunDetails> listRuns() {
        return jdbc.query(
                """
                SELECT r.id,r.snapshot_date,r.status,r.compared_to_id,r.started_at,r.finished_at,r.error_code,s.issues_count
                FROM kkd.kkd_run r JOIN kkd.run_statistics s ON s.run_id=r.id
                ORDER BY r.started_at DESC,r.id DESC
                """,
                (rs, row) ->
                        new RunDetails(
                                rs.getString("id"),
                                rs.getObject("snapshot_date", LocalDate.class),
                                RunStatus.valueOf(rs.getString("status")),
                                rs.getString("compared_to_id"),
                                rs.getObject("started_at", OffsetDateTime.class),
                                rs.getObject("finished_at", OffsetDateTime.class),
                                rs.getObject("issues_count", Long.class),
                                rs.getString("error_code")));
    }
}
