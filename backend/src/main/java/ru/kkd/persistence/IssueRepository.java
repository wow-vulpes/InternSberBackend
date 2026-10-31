package ru.kkd.persistence;

import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.kkd.domain.model.IssueDetails;
import ru.kkd.domain.model.IssueFilter;
import ru.kkd.domain.model.IssuePage;
import ru.kkd.domain.model.ObjectType;
import ru.kkd.domain.model.RuleCode;
import ru.kkd.domain.model.Severity;

@Repository
public class IssueRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public IssueRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public IssuePage page(IssueFilter filter) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("runId", filter.runId());
        parameters.put("limit", filter.limit());
        parameters.put("offset", filter.offset());
        StringBuilder conditions =
                new StringBuilder(" WHERE i.run_id=:runId AND g.status='ACTIVE'");
        if (filter.unitId() != null) {
            conditions.append(
                    " AND g.unit_id IN (SELECT unit_id FROM kkd.unit_subtree(:runId,:unitId))");
            parameters.put("unitId", filter.unitId());
        }
        if (filter.rule() != null) {
            conditions.append(" AND i.rule_code=:rule");
            parameters.put("rule", filter.rule().name());
        }
        if (filter.severity() != null) {
            conditions.append(" AND i.severity=:severity");
            parameters.put("severity", filter.severity().name());
        }
        String base =
                " FROM kkd.kkd_issue i JOIN kkd.goal_snapshot g ON g.run_id=i.run_id AND g.id=i.goal_id ";
        long total =
                jdbc.queryForObject("SELECT count(*)" + base + conditions, parameters, Long.class);
        String sql =
                """
                SELECT i.run_id,i.rule_code,r.title AS rule_title,i.severity,i.object_type,i.object_id,
                    CASE WHEN i.object_type='GOAL' THEN g.title ELSE k.title END AS object_title,
                    i.goal_id,g.title AS goal_title,g.unit_id,u.name AS unit_name
                """
                        + base
                        + """
                JOIN kkd.unit_snapshot u ON u.run_id=g.run_id AND u.id=g.unit_id
                JOIN kkd.kkd_rule r ON r.code=i.rule_code
                LEFT JOIN kkd.key_result_snapshot k ON k.run_id=i.run_id AND k.id=i.key_result_id
                """
                        + conditions
                        + " ORDER BY i.goal_id,i.rule_code,i.object_type,i.object_id LIMIT :limit OFFSET :offset";
        var items =
                jdbc.query(
                        sql,
                        parameters,
                        (rs, row) ->
                                new IssueDetails(
                                        rs.getString("run_id"),
                                        RuleCode.valueOf(rs.getString("rule_code")),
                                        rs.getString("rule_title"),
                                        Severity.valueOf(rs.getString("severity")),
                                        ObjectType.valueOf(rs.getString("object_type")),
                                        rs.getString("object_id"),
                                        rs.getString("object_title"),
                                        rs.getString("goal_id"),
                                        rs.getString("goal_title"),
                                        rs.getString("unit_id"),
                                        rs.getString("unit_name")));
        return new IssuePage(items, total, filter.limit(), filter.offset());
    }
}
