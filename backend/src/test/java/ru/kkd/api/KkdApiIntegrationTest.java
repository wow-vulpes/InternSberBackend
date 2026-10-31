package ru.kkd.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.kkd.application.DemoDataInitializer;
import ru.kkd.application.RunService;
import ru.kkd.domain.model.RunStatus;
import ru.kkd.support.PostgresTestSupport;

@SpringBootTest(properties = {"kkd.demo.enabled=false", "kkd.run.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class KkdApiIntegrationTest {

    private static final String FIRST = DemoDataInitializer.FIRST_RUN_ID;
    private static final String SECOND = DemoDataInitializer.SECOND_RUN_ID;
    private static final String SUMMARY = "/api/kkd/summary";
    private static final String ISSUES = "/api/kkd/issues";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            PostgresTestSupport.newContainer().withUsername("kkd");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoDataInitializer demo;
    @Autowired RunService runs;
    @Autowired ApplicationContext context;

    @Test
    void onlyHealthIsExposedWithoutDatabaseDetails() throws Exception {
        for (String path :
                List.of(
                        "/actuator/health",
                        "/actuator/health/readiness",
                        "/actuator/health/liveness")) {
            JsonNode health = request(path, parameters(), 200);
            assertThat(health.get("status").asText()).isEqualTo("UP");
            assertThat(health.has("components")).isFalse();
            assertThat(health.has("details")).isFalse();
        }
        for (String path :
                List.of("/actuator", "/actuator/env", "/actuator/beans", "/actuator/metrics")) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }

    @Test
    void readinessRefusesTrafficIndependentlyOfLiveness() throws Exception {
        try {
            AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
            JsonNode readiness = request("/actuator/health/readiness", parameters(), 503);
            assertThat(readiness.get("status").asText()).isEqualTo("OUT_OF_SERVICE");
            assertThat(
                            request("/actuator/health/liveness", parameters(), 200)
                                    .get("status")
                                    .asText())
                    .isEqualTo("UP");
        } finally {
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
        }
    }

    @BeforeEach
    void clearDatabase() {
        jdbc.execute("TRUNCATE kkd.kkd_run,kkd.unit,kkd.goal,kkd.key_result CASCADE");
    }

    @Test
    void emptyRunListIsAnArray() throws Exception {
        JsonNode result = request("/api/runs", parameters(), 200);
        assertThat(result.isArray()).isTrue();
        assertThat(result.size()).isZero();
    }

    @Test
    void demoRunListHasStableOrderIsoDatesAndExplicitNullFields() throws Exception {
        demo.initialize();
        JsonNode result = request("/api/runs", parameters(), 200);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.get(0).get("id").asText()).isEqualTo(SECOND);
        assertThat(result.get(1).get("id").asText()).isEqualTo(FIRST);
        for (JsonNode run : result) {
            assertFields(
                    run,
                    "id",
                    "snapshot_date",
                    "status",
                    "compared_to_id",
                    "started_at",
                    "finished_at",
                    "issues_count",
                    "error_code");
            assertThat(run.get("status").asText()).isEqualTo("completed");
            assertThat(run.get("error_code").isNull()).isTrue();
            OffsetDateTime started = OffsetDateTime.parse(run.get("started_at").asText());
            OffsetDateTime finished = OffsetDateTime.parse(run.get("finished_at").asText());
            assertThat(finished).isAfterOrEqualTo(started);
            LocalDate.parse(run.get("snapshot_date").asText());
        }
        assertThat(result.get(0).get("compared_to_id").asText()).isEqualTo(FIRST);
        assertThat(result.get(0).get("issues_count").asLong()).isEqualTo(8);
        assertThat(result.get(1).get("compared_to_id").isNull()).isTrue();
        assertThat(result.get(1).get("issues_count").asLong()).isEqualTo(10);
    }

    @Test
    void runListDoesNotPresentPartialResultsAsZeroAndBreaksTimeTiesById() throws Exception {
        jdbc.update(
                "INSERT INTO kkd.kkd_run(id,snapshot_date,started_at) VALUES ('A',DATE '2026-10-26',TIMESTAMPTZ '2026-10-26 10:00:00Z'),('Z',DATE '2026-10-26',TIMESTAMPTZ '2026-10-26 10:00:00Z'),('M',DATE '2026-10-26',TIMESTAMPTZ '2026-10-26 10:00:00Z')");
        jdbc.update(
                "UPDATE kkd.kkd_run SET status='COMPLETED',finished_at=TIMESTAMPTZ '2026-10-26 10:01:00Z' WHERE id='A'");
        jdbc.update(
                "UPDATE kkd.kkd_run SET status='FAILED',finished_at=TIMESTAMPTZ '2026-10-26 10:01:00Z',error_code='TEST_FAILURE' WHERE id='M'");
        JsonNode result = request("/api/runs", parameters(), 200);
        assertThat(result.get(0).get("id").asText()).isEqualTo("Z");
        assertThat(result.get(0).get("status").asText()).isEqualTo("running");
        assertThat(result.get(0).get("finished_at").isNull()).isTrue();
        assertThat(result.get(0).get("issues_count").isNull()).isTrue();
        assertThat(result.get(1).get("id").asText()).isEqualTo("M");
        assertThat(result.get(1).get("status").asText()).isEqualTo("failed");
        assertThat(result.get(1).get("issues_count").isNull()).isTrue();
        assertThat(result.get(1).get("error_code").asText()).isEqualTo("TEST_FAILURE");
        assertThat(result.get(2).get("id").asText()).isEqualTo("A");
        assertThat(result.get(2).get("issues_count").asLong()).isZero();
    }

    @Test
    void summarySerializesFullContractAndComputedDemoValues() throws Exception {
        demo.initialize();
        JsonNode result = request(SUMMARY, parameters("run_id", SECOND, "unit_id", "U1"), 200);
        assertFields(
                result, "run", "compared_to", "unit", "metrics", "issues_by_severity", "delta");
        assertFields(result.get("run"), "id", "snapshot_date");
        assertFields(result.get("compared_to"), "id", "snapshot_date");
        assertThat(result.get("run").get("id").asText()).isEqualTo(SECOND);
        assertThat(result.get("compared_to").get("id").asText()).isEqualTo(FIRST);
        assertFields(result.get("unit"), "id", "name", "parent_id");
        assertThat(result.get("unit").get("name").asText()).isEqualTo("Банк");
        assertThat(result.get("unit").get("parent_id").isNull()).isTrue();
        assertThat(result.get("metrics").size()).isEqualTo(3);
        for (JsonNode metric : result.get("metrics")) {
            assertFields(
                    metric, "code", "title", "current", "previous", "delta", "trend", "severity");
            assertThat(metric.get("title").asText()).isNotBlank();
            assertThat(metric.get("severity").asText()).isEqualTo("critical");
        }
        assertThat(result.get("metrics").get(0).get("code").asText())
                .isEqualTo("clean_goals_share");
        assertThat(result.get("metrics").get(0).get("current").decimalValue())
                .isEqualByComparingTo("65.2");
        assertThat(result.get("metrics").get(0).get("previous").decimalValue())
                .isEqualByComparingTo("65.2");
        assertThat(result.get("metrics").get(0).get("trend").asText()).isEqualTo("unchanged");
        JsonNode affected = result.get("metrics").get(1);
        assertThat(affected.get("code").asText()).isEqualTo("affected_goals_count");
        assertThat(affected.get("current").asLong()).isEqualTo(8);
        JsonNode issues = result.get("metrics").get(2);
        assertThat(issues.get("code").asText()).isEqualTo("issues_count");
        assertThat(issues.get("current").asLong()).isEqualTo(8);
        assertThat(issues.get("previous").asLong()).isEqualTo(10);
        assertThat(issues.get("delta").asLong()).isEqualTo(-2);
        assertThat(issues.get("trend").asText()).isEqualTo("improved");
        assertThat(result.get("issues_by_severity"))
                .isEqualTo(json.readTree("{\"critical\":3,\"major\":2,\"minor\":3}"));
        assertThat(result.get("delta"))
                .isEqualTo(json.readTree("{\"new\":3,\"persisting\":5,\"resolved\":5}"));
    }

    @Test
    void firstSummaryPreservesRequiredNullComparisonFields() throws Exception {
        demo.initialize();
        JsonNode result = request(SUMMARY, parameters("run_id", FIRST, "unit_id", "U1"), 200);
        assertThat(result.has("compared_to")).isTrue();
        assertThat(result.get("compared_to").isNull()).isTrue();
        for (JsonNode metric : result.get("metrics")) {
            for (String field : List.of("previous", "delta", "trend")) {
                assertThat(metric.has(field)).isTrue();
                assertThat(metric.get(field).isNull()).isTrue();
            }
        }
        assertFields(result.get("delta"), "new", "persisting", "resolved");
        for (JsonNode counter : result.get("delta")) {
            assertThat(counter.isNull()).isTrue();
        }
    }

    @Test
    void summaryUsesDescendantsAndKeepsUndefinedSeverityAndShareNull() throws Exception {
        demo.initialize();
        JsonNode middle = request(SUMMARY, parameters("run_id", SECOND, "unit_id", "U2"), 200);
        assertThat(middle.get("metrics").get(0).get("current").decimalValue())
                .isEqualByComparingTo("80.0");
        assertThat(middle.get("metrics").get(0).get("trend").asText()).isEqualTo("deteriorated");
        JsonNode leaf = request(SUMMARY, parameters("run_id", SECOND, "unit_id", "U3"), 200);
        for (JsonNode metric : leaf.get("metrics")) {
            assertThat(metric.has("severity")).isTrue();
            assertThat(metric.get("severity").isNull()).isTrue();
        }
        jdbc.update("INSERT INTO kkd.unit(id,name) VALUES ('EMPTY','Empty')");
        var next = runs.run(LocalDate.of(2026, 10, 27));
        JsonNode empty = request(SUMMARY, parameters("run_id", next.id(), "unit_id", "EMPTY"), 200);
        assertThat(empty.get("metrics").get(0).get("current").isNull()).isTrue();
        assertThat(empty.get("metrics").get(0).get("previous").isNull()).isTrue();
        assertThat(empty.get("metrics").get(2).get("current").asLong()).isZero();
        assertThat(empty.get("metrics").get(2).get("previous").asLong()).isZero();
        assertThat(empty.get("compared_to").get("id").asText()).isEqualTo(SECOND);
    }

    @Test
    void defaultIssuePageContainsHistoricalObjectAndGoalMetadata() throws Exception {
        demo.initialize();
        JsonNode result = request(ISSUES, parameters("run_id", SECOND), 200);
        assertFields(result, "items", "total", "limit", "offset");
        assertThat(result.get("total").asLong()).isEqualTo(8);
        assertThat(result.get("limit").asInt()).isEqualTo(50);
        assertThat(result.get("offset").asInt()).isZero();
        assertThat(result.get("items").size()).isEqualTo(8);
        JsonNode goal = result.get("items").get(0);
        assertFields(
                goal,
                "run_id",
                "rule_code",
                "rule_title",
                "severity",
                "object_type",
                "object_id",
                "object_title",
                "goal_id",
                "goal_title",
                "unit_id",
                "unit_name");
        assertThat(goal.get("run_id").asText()).isEqualTo(SECOND);
        assertThat(goal.get("rule_code").asText()).isEqualTo("KR_WEIGHTS_SUM");
        assertThat(goal.get("rule_title").asText()).isEqualTo("Сумма весов КР не равна 100%");
        assertThat(goal.get("severity").asText()).isEqualTo("major");
        assertThat(goal.get("object_type").asText()).isEqualTo("goal");
        assertThat(goal.get("object_id").asText()).isEqualTo("G03");
        assertThat(goal.get("object_title").asText()).isEqualTo("Демонстрационная цель 03");
        JsonNode kr = result.get("items").get(1);
        assertThat(kr.get("object_type").asText()).isEqualTo("key_result");
        assertThat(kr.get("object_id").asText()).isEqualTo("KR04");
        assertThat(kr.get("object_title").asText()).isEqualTo("Ключевой результат 04");
        assertThat(kr.get("goal_id").asText()).isEqualTo("G04");
        assertThat(kr.get("goal_title").asText()).isEqualTo("Демонстрационная цель 04");
        assertThat(kr.get("unit_id").asText()).isEqualTo("U1");
        assertThat(kr.get("unit_name").asText()).isEqualTo("Банк");
    }

    @ParameterizedTest
    @CsvSource({
        "U1,,,8", "U2,,,3", "U3,,,0", ",KR_STALE_PROGRESS,,3", ",GOAL_NO_OWNER,,2",
        ",GOAL_NO_KR,,1", ",KR_WEIGHTS_SUM,,2", ",,critical,3", ",,MAJOR,2", ",,MiNoR,3",
        "U2,KR_STALE_PROGRESS,minor,2", "U2,KR_WEIGHTS_SUM,major,0", "U2,GOAL_NO_OWNER,critical,0"
    })
    void issueFiltersComposeAndTotalReflectsAllMatchingRows(
            String unit, String rule, String severity, long total) throws Exception {
        demo.initialize();
        var parameters = parameters("run_id", SECOND);
        if (unit != null) parameters.add("unit_id", unit);
        if (rule != null) parameters.add("rule", rule);
        if (severity != null) parameters.add("severity", severity);
        JsonNode result = request(ISSUES, parameters, 200);
        assertThat(result.get("total").asLong()).isEqualTo(total);
        assertThat(result.get("items").size()).isEqualTo((int) total);
        for (JsonNode issue : result.get("items")) {
            if (rule != null) assertThat(issue.get("rule_code").asText()).isEqualTo(rule);
            if (severity != null)
                assertThat(issue.get("severity").asText())
                        .isEqualTo(severity.toLowerCase(Locale.ROOT));
            if ("U2".equals(unit)) assertThat(issue.get("unit_id").asText()).isIn("U2", "U3");
        }
    }

    @Test
    void pagesHaveStableOrderNoDuplicatesAndTotalIndependentOfOffset() throws Exception {
        demo.initialize();
        List<String> expected = issueKeys(request(ISSUES, parameters("run_id", SECOND), 200));
        List<String> actual = new ArrayList<>();
        for (int offset = 0; offset < 9; offset += 3) {
            JsonNode page =
                    request(
                            ISSUES,
                            parameters(
                                    "run_id",
                                    SECOND,
                                    "limit",
                                    "3",
                                    "offset",
                                    Integer.toString(offset)),
                            200);
            assertThat(page.get("total").asLong()).isEqualTo(8);
            assertThat(page.get("offset").asInt()).isEqualTo(offset);
            actual.addAll(issueKeys(page));
        }
        assertThat(actual).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        assertThat(
                        request(ISSUES, parameters("run_id", SECOND, "limit", "200"), 200)
                                .get("items")
                                .size())
                .isEqualTo(8);
        JsonNode beyondEnd =
                request(ISSUES, parameters("run_id", SECOND, "offset", "2147483647"), 200);
        assertThat(beyondEnd.get("items").size()).isZero();
        assertThat(beyondEnd.get("total").asLong()).isEqualTo(8);
        JsonNode filtered =
                request(
                        ISSUES,
                        parameters("run_id", SECOND, "unit_id", "U2", "limit", "1", "offset", "1"),
                        200);
        assertThat(filtered.get("total").asLong()).isEqualTo(3);
        assertThat(filtered.get("items").get(0).get("goal_id").asText()).isEqualTo("G10");
    }

    @Test
    void currentSourceEditsAndLaterRunDoNotChangeRequestedHistoricalResponses() throws Exception {
        demo.initialize();
        var issueParams = parameters("run_id", SECOND, "unit_id", "U2");
        var summaryParams = parameters("run_id", SECOND, "unit_id", "U2");
        JsonNode oldIssues = request(ISSUES, issueParams, 200);
        JsonNode oldSummary = request(SUMMARY, summaryParams, 200);
        jdbc.update("UPDATE kkd.key_result SET title='Changed KR'");
        jdbc.update("UPDATE kkd.goal SET title='Changed goal',status='INACTIVE'");
        jdbc.update("UPDATE kkd.unit SET name='Changed unit',parent_id=NULL");
        runs.run(LocalDate.of(2026, 10, 27));
        assertThat(request(ISSUES, issueParams, 200)).isEqualTo(oldIssues);
        assertThat(request(SUMMARY, summaryParams, 200)).isEqualTo(oldSummary);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidParametersHaveOneErrorContract(
            String path, MultiValueMap<String, String> parameters) throws Exception {
        assertError(request(path, parameters, 422), "INVALID_PARAMETER");
    }

    static Stream<Arguments> invalidRequests() {
        List<Arguments> cases = new ArrayList<>();
        cases.add(Arguments.of(SUMMARY, parameters()));
        cases.add(Arguments.of(SUMMARY, parameters("run_id", SECOND)));
        cases.add(Arguments.of(SUMMARY, parameters("unit_id", "U1")));
        cases.add(Arguments.of(SUMMARY, parameters("run_id", " ", "unit_id", "U1")));
        cases.add(Arguments.of(SUMMARY, parameters("run_id", SECOND, "unit_id", "")));
        cases.add(
                Arguments.of(SUMMARY, parameters("run_id", SECOND, "unit_id", "U1", "limit", "1")));
        cases.add(
                Arguments.of(
                        SUMMARY, parameters("run_id", FIRST, "run_id", SECOND, "unit_id", "U1")));
        cases.add(Arguments.of(ISSUES, parameters()));
        cases.add(Arguments.of(ISSUES, parameters("run_id", "")));
        cases.add(Arguments.of(ISSUES, parameters("run_id", SECOND, "unit_id", "\t")));
        cases.add(
                Arguments.of(
                        ISSUES, parameters("run_id", SECOND, "unit_id", "U1", "unit_id", "U2")));
        cases.add(Arguments.of(ISSUES, parameters("run_id", SECOND, "unexpected", "value")));
        cases.add(
                Arguments.of(
                        ISSUES,
                        parameters("run_id", SECOND, "severity", "critical", "severity", "minor")));
        cases.add(Arguments.of(ISSUES, parameters("run_id", SECOND, "limit", "1", "limit", "2")));
        cases.add(Arguments.of("/api/runs", parameters("limit", "1")));
        for (String limit :
                List.of(
                        "0",
                        "201",
                        "-1",
                        "1.5",
                        "1e2",
                        "NaN",
                        "abc",
                        "",
                        " ",
                        "+1",
                        "2147483648")) {
            cases.add(Arguments.of(ISSUES, parameters("run_id", SECOND, "limit", limit)));
        }
        for (String offset : List.of("-1", "0.1", "abc", "", " 0", "2147483648")) {
            cases.add(Arguments.of(ISSUES, parameters("run_id", SECOND, "offset", offset)));
        }
        for (String rule : List.of("UNKNOWN", "goal_no_owner", " GOAL_NO_OWNER", "")) {
            cases.add(Arguments.of(ISSUES, parameters("run_id", SECOND, "rule", rule)));
        }
        for (String severity : List.of("warning", " critical", "critical,minor", "")) {
            cases.add(Arguments.of(ISSUES, parameters("run_id", SECOND, "severity", severity)));
        }
        return cases.stream();
    }

    @Test
    void missingRunAndUnitReturn404InsteadOfEmptySuccess() throws Exception {
        for (String path : List.of(SUMMARY, ISSUES)) {
            assertError(
                    request(path, parameters("run_id", "unknown", "unit_id", "U1"), 404),
                    "RUN_NOT_FOUND");
        }
        demo.initialize();
        for (String path : List.of(SUMMARY, ISSUES)) {
            assertError(
                    request(path, parameters("run_id", SECOND, "unit_id", "unknown"), 404),
                    "UNIT_NOT_FOUND");
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = RunStatus.class,
            names = {"RUNNING", "FAILED"})
    void partialRunsHaveExplicit422StateError(RunStatus status) throws Exception {
        jdbc.update(
                "INSERT INTO kkd.kkd_run(id,snapshot_date) VALUES ('PARTIAL',DATE '2026-10-26')");
        if (status == RunStatus.FAILED) {
            jdbc.update(
                    "UPDATE kkd.kkd_run SET status='FAILED',finished_at=clock_timestamp(),error_code='TEST_FAILURE' WHERE id='PARTIAL'");
        }
        for (String path : List.of(SUMMARY, ISSUES)) {
            assertError(
                    request(path, parameters("run_id", "PARTIAL", "unit_id", "U1"), 422),
                    "RUN_NOT_COMPLETED");
        }
    }

    @Test
    void idValuesAreBoundAsDataRatherThanSql() throws Exception {
        demo.initialize();
        assertError(request(ISSUES, parameters("run_id", "' OR '1'='1"), 404), "RUN_NOT_FOUND");
        assertError(
                request(ISSUES, parameters("run_id", SECOND, "unit_id", "' OR '1'='1"), 404),
                "UNIT_NOT_FOUND");
        assertThat(request(ISSUES, parameters("run_id", SECOND), 200).get("total").asLong())
                .isEqualTo(8);
    }

    @Test
    void unknownRouteWrongMethodAndUnsupportedAcceptUseJsonErrors() throws Exception {
        assertError(request("/api/unknown", parameters(), 404), "NOT_FOUND");
        MvcResult wrongMethod =
                mvc.perform(post("/api/runs"))
                        .andExpect(status().isMethodNotAllowed())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andReturn();
        assertError(
                json.readTree(wrongMethod.getResponse().getContentAsByteArray()),
                "METHOD_NOT_ALLOWED");
        assertThat(wrongMethod.getResponse().getHeader("Allow")).contains("GET");
        MvcResult unacceptable =
                mvc.perform(get("/api/runs").accept(MediaType.TEXT_PLAIN))
                        .andExpect(status().isNotAcceptable())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andReturn();
        assertError(
                json.readTree(unacceptable.getResponse().getContentAsByteArray()),
                "NOT_ACCEPTABLE");
    }

    private JsonNode request(
            String path, MultiValueMap<String, String> parameters, int expectedStatus)
            throws Exception {
        MvcResult result =
                mvc.perform(get(path).params(parameters).accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().is(expectedStatus))
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private List<String> issueKeys(JsonNode page) {
        List<String> keys = new ArrayList<>();
        for (JsonNode issue : page.get("items")) {
            keys.add(
                    issue.get("rule_code").asText()
                            + ":"
                            + issue.get("object_type").asText()
                            + ":"
                            + issue.get("object_id").asText());
        }
        return keys;
    }

    private void assertError(JsonNode result, String code) {
        assertFields(result, "error");
        assertFields(result.get("error"), "code", "message");
        assertThat(result.get("error").get("code").asText()).isEqualTo(code);
        assertThat(result.get("error").get("message").asText()).isNotBlank();
    }

    private void assertFields(JsonNode node, String... expected) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder(expected);
    }

    private static MultiValueMap<String, String> parameters(String... pairs) {
        var result = new LinkedMultiValueMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.add(pairs[i], pairs[i + 1]);
        }
        return result;
    }
}
