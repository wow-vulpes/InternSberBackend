package ru.kkd.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IssueTest {

    @Test
    void rejectsMissingRuleCode() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Issue(null, ObjectType.GOAL, "G1", "G1", "U1"))
                .withMessageContaining("Rule code");
    }

    @Test
    void rejectsMissingObjectType() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Issue(RuleCode.GOAL_NO_OWNER, null, "G1", "G1", "U1"))
                .withMessageContaining("Object type");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingObjectId(String id) {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> new Issue(RuleCode.GOAL_NO_OWNER, ObjectType.GOAL, id, "G1", "U1"))
                .withMessageContaining("Object id");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingGoalId(String id) {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> new Issue(RuleCode.GOAL_NO_OWNER, ObjectType.GOAL, "G1", id, "U1"))
                .withMessageContaining("Goal id");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingUnitId(String id) {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> new Issue(RuleCode.GOAL_NO_OWNER, ObjectType.GOAL, "G1", "G1", id))
                .withMessageContaining("Unit id");
    }

    @ParameterizedTest
    @CsvSource({
        "GOAL_NO_OWNER, CRITICAL",
        "GOAL_NO_KR, CRITICAL",
        "KR_WEIGHTS_SUM, MAJOR",
        "KR_STALE_PROGRESS, MINOR"
    })
    void determinesSeverityFromRuleCode(RuleCode rule, Severity expected) {
        ObjectType type =
                rule == RuleCode.KR_STALE_PROGRESS ? ObjectType.KEY_RESULT : ObjectType.GOAL;
        Issue issue = new Issue(rule, type, "OBJECT-1", "GOAL-1", "UNIT-1");

        assertThat(issue.severity()).isEqualTo(expected);
    }
}
