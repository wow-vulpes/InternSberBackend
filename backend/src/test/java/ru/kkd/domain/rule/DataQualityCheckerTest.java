package ru.kkd.domain.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.kkd.domain.model.GoalSnapshot;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.KeyResultSnapshot;
import ru.kkd.domain.model.ObjectType;
import ru.kkd.domain.model.RuleCode;

class DataQualityCheckerTest {

    private static final LocalDate SNAPSHOT_DATE = LocalDate.of(2026, 10, 26);
    private final DataQualityChecker checker = new DataQualityChecker();

    @Test
    void rejectsMissingGoals() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> checker.check(null, SNAPSHOT_DATE))
                .withMessageContaining("Goals");
    }

    @Test
    void rejectsMissingSnapshotDate() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> checker.check(List.of(), null))
                .withMessageContaining("Snapshot date");
    }

    @Test
    void rejectsNullGoalElement() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                checker.check(
                                        Arrays.asList(goal("G1", true, "K1"), null), SNAPSHOT_DATE))
                .withMessageContaining("Goal");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsDuplicateGoalIdsRegardlessOfActivity(boolean active) {
        List<GoalSnapshot> goals = List.of(goal("G1", true, "K1"), goal("G1", active, "K2"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> checker.check(goals, SNAPSHOT_DATE))
                .withMessageContaining("Goal ids must be unique");
    }

    @Test
    void rejectsDuplicateKeyResultIdsWithinGoal() {
        KeyResultSnapshot first = new KeyResultSnapshot("K1", BigDecimal.TEN, null);
        KeyResultSnapshot second = new KeyResultSnapshot("K1", BigDecimal.ONE, SNAPSHOT_DATE);
        GoalSnapshot goal = new GoalSnapshot("G1", "U1", "owner", true, List.of(first, second));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> checker.check(List.of(goal), SNAPSHOT_DATE))
                .withMessageContaining("Key result ids must be unique");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsDuplicateKeyResultIdsAcrossGoalsRegardlessOfActivity(boolean active) {
        List<GoalSnapshot> goals = List.of(goal("G1", true, "K1"), goal("G2", active, "K1"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> checker.check(goals, SNAPSHOT_DATE))
                .withMessageContaining("Key result ids must be unique");
    }

    @Test
    void validationDoesNotChangeCallerData() {
        GoalSnapshot first = goal("G1", true, "K1");
        GoalSnapshot second = goal("G1", false, "K2");
        List<GoalSnapshot> goals = new ArrayList<>(List.of(first, second));

        assertThatIllegalArgumentException().isThrownBy(() -> checker.check(goals, SNAPSHOT_DATE));

        assertThat(goals).containsExactly(first, second);
        assertThat(first.keyResults()).extracting(KeyResultSnapshot::id).containsExactly("K1");
        assertThat(second.keyResults()).extracting(KeyResultSnapshot::id).containsExactly("K2");
    }

    @ParameterizedTest
    @MethodSource("validInputs")
    void acceptsValidInputsAndPreservesObjectIdentity(
            List<GoalSnapshot> goals, List<Issue> expected) {
        assertThat(checker.check(goals, SNAPSHOT_DATE)).containsExactlyElementsOf(expected);
    }

    private static Stream<Arguments> validInputs() {
        return Stream.of(
                Arguments.of(List.of(), List.of()),
                Arguments.of(
                        List.of(goal("SAME-ID", true, "SAME-ID")),
                        List.of(
                                new Issue(
                                        RuleCode.GOAL_NO_OWNER,
                                        ObjectType.GOAL,
                                        "SAME-ID",
                                        "SAME-ID",
                                        "U1"),
                                new Issue(
                                        RuleCode.KR_STALE_PROGRESS,
                                        ObjectType.KEY_RESULT,
                                        "SAME-ID",
                                        "SAME-ID",
                                        "U1"))),
                Arguments.of(
                        List.of(goal("G1", true, "K1"), goal("G2", false, "K2")),
                        List.of(
                                new Issue(
                                        RuleCode.GOAL_NO_OWNER, ObjectType.GOAL, "G1", "G1", "U1"),
                                new Issue(
                                        RuleCode.KR_STALE_PROGRESS,
                                        ObjectType.KEY_RESULT,
                                        "K1",
                                        "G1",
                                        "U1"))));
    }

    private static GoalSnapshot goal(String id, boolean active, String keyResultId) {
        return new GoalSnapshot(
                id,
                "U1",
                null,
                active,
                List.of(new KeyResultSnapshot(keyResultId, new BigDecimal("100"), null)));
    }
}
