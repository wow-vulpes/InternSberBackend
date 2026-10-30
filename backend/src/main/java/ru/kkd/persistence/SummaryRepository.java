package ru.kkd.persistence;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.kkd.domain.model.IssueIdentity;
import ru.kkd.domain.model.ObjectType;
import ru.kkd.domain.model.QualitySnapshot;
import ru.kkd.domain.model.RuleCode;
import ru.kkd.domain.model.Severity;
import ru.kkd.domain.model.SnapshotIssue;
import ru.kkd.domain.model.UnitSnapshot;

@Repository
public class SummaryRepository {

    private final JdbcTemplate jdbc;

    public SummaryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UnitSnapshot> findUnit(String runId, String unitId) {
        return jdbc
                .query(
                        "SELECT id,name,parent_id FROM kkd.unit_snapshot WHERE run_id=? AND id=?",
                        (rs, row) ->
                                new UnitSnapshot(
                                        rs.getString("id"),
                                        rs.getString("name"),
                                        rs.getString("parent_id")),
                        runId,
                        unitId)
                .stream()
                .findFirst();
    }

    public QualitySnapshot loadQualitySnapshot(String runId, String unitId) {
        Set<String> goalIds =
                new HashSet<>(
                        jdbc.queryForList(
                                """
                SELECT g.id FROM kkd.goal_snapshot g
                JOIN kkd.unit_subtree(?,?) scope ON scope.unit_id=g.unit_id
                WHERE g.run_id=? AND g.status='ACTIVE'
                """,
                                String.class,
                                runId,
                                unitId,
                                runId));
        List<SnapshotIssue> issues =
                jdbc.query(
                        """
                SELECT i.rule_code,i.object_type,i.object_id,i.goal_id,i.severity
                FROM kkd.kkd_issue i
                JOIN kkd.goal_snapshot g ON g.run_id=i.run_id AND g.id=i.goal_id
                JOIN kkd.unit_subtree(?,?) scope ON scope.unit_id=g.unit_id
                WHERE i.run_id=? AND g.status='ACTIVE'
                ORDER BY i.rule_code,i.object_type,i.object_id
                """,
                        (rs, row) ->
                                new SnapshotIssue(
                                        new IssueIdentity(
                                                RuleCode.valueOf(rs.getString("rule_code")),
                                                ObjectType.valueOf(rs.getString("object_type")),
                                                rs.getString("object_id")),
                                        rs.getString("goal_id"),
                                        Severity.valueOf(rs.getString("severity"))),
                        runId,
                        unitId,
                        runId);
        return new QualitySnapshot(goalIds, issues);
    }
}
