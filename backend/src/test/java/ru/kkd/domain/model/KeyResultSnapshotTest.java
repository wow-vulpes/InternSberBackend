package ru.kkd.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class KeyResultSnapshotTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingId(String id) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new KeyResultSnapshot(id, BigDecimal.ONE, null))
                .withMessageContaining("Key result id");
    }

    @Test
    void rejectsMissingWeight() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new KeyResultSnapshot("K1", null, null))
                .withMessageContaining("Key result weight");
    }

    @Test
    void preservesMissingProgressDateForQualityRules() {
        KeyResultSnapshot keyResult = new KeyResultSnapshot("K1", BigDecimal.ONE, null);

        assertThat(keyResult.progressUpdatedOn()).isNull();
    }

    @Test
    void doesNotUseSystemDateForValidation() {
        LocalDate futureDate = LocalDate.of(2100, 1, 1);

        KeyResultSnapshot keyResult = new KeyResultSnapshot("K1", BigDecimal.ONE, futureDate);

        assertThat(keyResult.progressUpdatedOn()).isEqualTo(futureDate);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "101", "33.3300"})
    void preservesWeightWithoutRangeValidationOrRounding(String value) {
        BigDecimal weight = new BigDecimal(value);

        KeyResultSnapshot keyResult = new KeyResultSnapshot("K1", weight, null);

        assertThat(keyResult.weight()).isEqualTo(weight);
    }
}
