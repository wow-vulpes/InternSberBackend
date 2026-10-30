package ru.kkd.domain.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.kkd.domain.model.GoalSnapshot;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.KeyResultSnapshot;
import ru.kkd.domain.model.ObjectType;
import ru.kkd.domain.model.RuleCode;

class DataQualityRulesTest {

    private static final LocalDate SNAPSHOT_DATE = LocalDate.of(2026, 10, 26);
    private final DataQualityChecker checker = new DataQualityChecker();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void findsMissingOwner(String owner) {
        GoalSnapshot goal = goal(owner, keyResult("K1", "100", SNAPSHOT_DATE));

        assertThat(checker.check(List.of(goal), SNAPSHOT_DATE))
                .containsExactly(goalIssue(RuleCode.GOAL_NO_OWNER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "  owner  "})
    void acceptsPresentOwner(String owner) {
        GoalSnapshot goal = goal(owner, keyResult("K1", "100", SNAPSHOT_DATE));

        assertThat(checker.check(List.of(goal), SNAPSHOT_DATE)).isEmpty();
    }

    @Test
    void findsMissingKeyResultsWithoutWeightIssue() {
        assertThat(checker.check(List.of(goal("owner")), SNAPSHOT_DATE))
                .containsExactly(goalIssue(RuleCode.GOAL_NO_KR));
    }

    @ParameterizedTest
    @CsvSource({"100, 0", "100.00, 0.000", "33.33, 66.67", "0.1, 99.9", "-10, 110"})
    void acceptsExactWeightSumRegardlessOfScale(String first, String second) {
        GoalSnapshot goal =
                goal(
                        "owner",
                        keyResult("K1", first, SNAPSHOT_DATE),
                        keyResult("K2", second, SNAPSHOT_DATE));

        assertThat(checker.check(List.of(goal), SNAPSHOT_DATE)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"99.99, 0", "100.01, 0", "33.33, 66.66", "0, 0", "99.99999999999999999999, 0"})
    void findsIncorrectWeightSumWithoutRounding(String first, String second) {
        GoalSnapshot goal =
                goal(
                        "owner",
                        keyResult("K1", first, SNAPSHOT_DATE),
                        keyResult("K2", second, SNAPSHOT_DATE));

        assertThat(checker.check(List.of(goal), SNAPSHOT_DATE))
                .containsExactly(goalIssue(RuleCode.KR_WEIGHTS_SUM));
    }

    @ParameterizedTest
    @CsvSource({"0, false", "44, false", "45, false", "46, true", "100, true", "-1, false"})
    void checksProgressAgeAgainstSnapshot(int days, boolean expectedIssue) {
        GoalSnapshot goal = goal("owner", keyResult("K1", "100", SNAPSHOT_DATE.minusDays(days)));

        assertThat(checker.check(List.of(goal), SNAPSHOT_DATE))
                .containsExactlyElementsOf(
                        expectedIssue ? List.of(keyResultIssue("K1")) : List.of());
    }

    @Test
    void findsProgressThatWasNeverUpdated() {
        GoalSnapshot goal = goal("owner", keyResult("K1", "100", null));

        assertThat(checker.check(List.of(goal), SNAPSHOT_DATE))
                .containsExactly(keyResultIssue("K1"));
    }

    @ParameterizedTest
    @CsvSource({
        "2026-03-01, 2026-01-15, false",
        "2026-03-01, 2026-01-14, true",
        "2026-01-10, 2025-11-26, false",
        "2026-01-10, 2025-11-25, true",
        "2024-03-01, 2024-01-16, false",
        "2024-03-01, 2024-01-15, true"
    })
    void handlesMonthYearAndLeapYearBoundaries(
            LocalDate snapshot, LocalDate updatedOn, boolean expectedIssue) {
        GoalSnapshot goal = goal("owner", keyResult("K1", "100", updatedOn));

        assertThat(checker.check(List.of(goal), snapshot))
                .containsExactlyElementsOf(
                        expectedIssue ? List.of(keyResultIssue("K1")) : List.of());
    }

    @ParameterizedTest
    @MethodSource("dateLimits")
    void handlesSupportedDateLimits(
            LocalDate snapshot, LocalDate updatedOn, boolean expectedIssue) {
        GoalSnapshot goal = goal("owner", keyResult("K1", "100", updatedOn));

        assertThat(checker.check(List.of(goal), snapshot))
                .containsExactlyElementsOf(
                        expectedIssue ? List.of(keyResultIssue("K1")) : List.of());
    }

    @Test
    void findsEachStaleKeyResultAndKeepsGoalAndUnitLinks() {
        GoalSnapshot goal =
                new GoalSnapshot(
                        "G77",
                        "U77",
                        "owner",
                        true,
                        List.of(
                                keyResult("K2", "50", SNAPSHOT_DATE.minusDays(46)),
                                keyResult("K1", "50", null)));

        assertThat(checker.check(List.of(goal), SNAPSHOT_DATE))
                .containsExactly(
                        new Issue(
                                RuleCode.KR_STALE_PROGRESS,
                                ObjectType.KEY_RESULT,
                                "K1",
                                "G77",
                                "U77"),
                        new Issue(
                                RuleCode.KR_STALE_PROGRESS,
                                ObjectType.KEY_RESULT,
                                "K2",
                                "G77",
                                "U77"));
    }

    @Test
    void ignoresInactiveGoalsAndTheirKeyResults() {
        List<GoalSnapshot> goals =
                List.of(
                        new GoalSnapshot("G2", "U1", null, false, List.of()),
                        new GoalSnapshot(
                                "G3", "U1", null, false, List.of(keyResult("K3", "1", null))),
                        goal("owner", keyResult("K1", "100", SNAPSHOT_DATE)));

        assertThat(checker.check(goals, SNAPSHOT_DATE)).isEmpty();
    }

    @Test
    void returnsAllViolationsInStableOrder() {
        GoalSnapshot second = new GoalSnapshot("G2", "U2", null, true, List.of());
        GoalSnapshot first =
                goal(
                        null,
                        keyResult("K2", "30", null),
                        keyResult("K1", "30", SNAPSHOT_DATE.minusDays(46)));

        assertThat(checker.check(List.of(second, first), SNAPSHOT_DATE))
                .containsExactly(
                        goalIssue(RuleCode.GOAL_NO_OWNER),
                        keyResultIssue("K1"),
                        keyResultIssue("K2"),
                        goalIssue(RuleCode.KR_WEIGHTS_SUM),
                        new Issue(RuleCode.GOAL_NO_KR, ObjectType.GOAL, "G2", "G2", "U2"),
                        new Issue(RuleCode.GOAL_NO_OWNER, ObjectType.GOAL, "G2", "G2", "U2"));
    }

    @Test
    void producesSameResultForDifferentInputOrderAndRepeatedCalls() {
        GoalSnapshot first = goal(null, keyResult("K1", "100", null));
        GoalSnapshot second = new GoalSnapshot("G2", "U2", "owner", true, List.of());
        List<Issue> expected =
                List.of(
                        goalIssue(RuleCode.GOAL_NO_OWNER),
                        keyResultIssue("K1"),
                        new Issue(RuleCode.GOAL_NO_KR, ObjectType.GOAL, "G2", "G2", "U2"));

        assertThat(checker.check(List.of(second, first), SNAPSHOT_DATE)).isEqualTo(expected);
        assertThat(checker.check(List.of(first, second), SNAPSHOT_DATE)).isEqualTo(expected);
        assertThat(checker.check(List.of(second, first), SNAPSHOT_DATE)).isEqualTo(expected);
    }

    @Test
    void usesDateOfEachCallWithoutKeepingPreviousResults() {
        List<GoalSnapshot> goals =
                List.of(goal("owner", keyResult("K1", "100", SNAPSHOT_DATE.minusDays(45))));

        assertThat(checker.check(goals, SNAPSHOT_DATE)).isEmpty();
        assertThat(checker.check(goals, SNAPSHOT_DATE.plusDays(1)))
                .containsExactly(keyResultIssue("K1"));
        assertThat(checker.check(goals, SNAPSHOT_DATE)).isEmpty();
    }

    @Test
    void doesNotChangeCallerDataDuringSuccessfulCheck() {
        GoalSnapshot second = new GoalSnapshot("G2", "U2", "owner", true, List.of());
        GoalSnapshot first =
                goal("owner", keyResult("K2", "50", null), keyResult("K1", "50", SNAPSHOT_DATE));
        List<GoalSnapshot> goals = new ArrayList<>(List.of(second, first));

        checker.check(goals, SNAPSHOT_DATE);

        assertThat(goals).containsExactly(second, first);
        assertThat(first.keyResults())
                .extracting(KeyResultSnapshot::id)
                .containsExactly("K2", "K1");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void returnsImmutableResultsIncludingEmptyResult(boolean withIssue) {
        GoalSnapshot goal = goal(withIssue ? null : "owner", keyResult("K1", "100", SNAPSHOT_DATE));
        List<Issue> issues = checker.check(List.of(goal), SNAPSHOT_DATE);

        assertThatThrownBy(() -> issues.add(goalIssue(RuleCode.GOAL_NO_KR)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static Stream<Arguments> dateLimits() {
        return Stream.of(
                Arguments.of(LocalDate.MIN, LocalDate.MIN, false),
                Arguments.of(LocalDate.MAX, LocalDate.MAX, false),
                Arguments.of(LocalDate.MAX, LocalDate.MIN, true),
                Arguments.of(LocalDate.MIN, LocalDate.MAX, false));
    }

    private GoalSnapshot goal(String owner, KeyResultSnapshot... keyResults) {
        return new GoalSnapshot("G1", "U1", owner, true, List.of(keyResults));
    }

    private KeyResultSnapshot keyResult(String id, String weight, LocalDate updatedOn) {
        return new KeyResultSnapshot(id, new BigDecimal(weight), updatedOn);
    }

    private Issue goalIssue(RuleCode rule) {
        return new Issue(rule, ObjectType.GOAL, "G1", "G1", "U1");
    }

    private Issue keyResultIssue(String id) {
        return new Issue(RuleCode.KR_STALE_PROGRESS, ObjectType.KEY_RESULT, id, "G1", "U1");
    }
}
