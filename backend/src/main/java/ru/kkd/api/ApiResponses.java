package ru.kkd.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class ApiResponses {

    private ApiResponses() {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Run(
            String id,
            LocalDate snapshotDate,
            String status,
            String comparedToId,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            Long issuesCount,
            String errorCode) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RunRef(String id, LocalDate snapshotDate) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Unit(String id, String name, String parentId) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Metric(
            String key,
            String title,
            String unit,
            BigDecimal value,
            BigDecimal previous,
            BigDecimal delta,
            String trend,
            String severity) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SeverityComparison(String code, long current, Long previous, Long delta) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Delta(
            @JsonProperty("new") Long newCount,
            @JsonProperty("persisting") Long persistingCount,
            @JsonProperty("resolved") Long resolvedCount) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Summary(
            RunRef run,
            RunRef comparedTo,
            Unit unit,
            List<Metric> metrics,
            List<SeverityComparison> issuesBySeverity,
            Delta delta) {
        public Summary {
            metrics = List.copyOf(metrics);
            issuesBySeverity = List.copyOf(issuesBySeverity);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Issue(
            String runId,
            String ruleCode,
            String ruleTitle,
            String severity,
            String objectType,
            String objectId,
            String objectTitle,
            String goalId,
            String goalTitle,
            String unitId,
            String unitName) {}

    public record Issues(List<Issue> items, long total, int limit, int offset) {
        public Issues {
            items = List.copyOf(items);
        }
    }

    public record ErrorResponse(ErrorBody error) {}

    public record ErrorBody(String code, String message) {}
}
