package ru.kkd.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record KeyResultSnapshot(String id, BigDecimal weight, LocalDate progressUpdatedOn) {

    public KeyResultSnapshot {
        ModelValidation.requireId(id, "Key result id");
        ModelValidation.requireNonNull(weight, "Key result weight");
    }
}
