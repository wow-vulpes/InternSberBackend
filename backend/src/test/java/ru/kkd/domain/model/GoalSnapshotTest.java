package ru.kkd.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class GoalSnapshotTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingGoalId(String id) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GoalSnapshot(id, "U1", "owner", true, List.of()))
                .withMessageContaining("Goal id");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingUnitId(String unitId) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GoalSnapshot("G1", unitId, "owner", true, List.of()))
                .withMessageContaining("Unit id");
    }

    @Test
    void rejectsNullKeyResults() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GoalSnapshot("G1", "U1", "owner", true, null))
                .withMessageContaining("Key results");
    }

    @Test
    void rejectsNullKeyResultElement() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new GoalSnapshot(
                                        "G1",
                                        "U1",
                                        "owner",
                                        true,
                                        Arrays.asList(keyResult(), null)))
                .withMessageContaining("Key result");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void preservesMissingOwnerForQualityRules(String owner) {
        GoalSnapshot goal = new GoalSnapshot("G1", "U1", owner, true, List.of());

        assertThat(goal.owner()).isEqualTo(owner);
    }

    @Test
    void copiesKeyResultsWithoutChangingCallerList() {
        KeyResultSnapshot keyResult = keyResult();
        List<KeyResultSnapshot> source = new ArrayList<>(List.of(keyResult));
        GoalSnapshot goal = new GoalSnapshot("G1", "U1", "owner", true, source);

        assertThat(source).containsExactly(keyResult);
        source.clear();

        assertThat(goal.keyResults()).containsExactly(keyResult);
    }

    @Test
    void doesNotExposeMutableKeyResults() {
        GoalSnapshot goal = new GoalSnapshot("G1", "U1", "owner", true, List.of(keyResult()));

        assertThatThrownBy(() -> goal.keyResults().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private KeyResultSnapshot keyResult() {
        return new KeyResultSnapshot("K1", new BigDecimal("100"), LocalDate.of(2026, 10, 26));
    }
}
