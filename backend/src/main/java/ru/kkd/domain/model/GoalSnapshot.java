package ru.kkd.domain.model;

import java.util.List;

public record GoalSnapshot(
        String id,
        String unitId,
        String owner,
        boolean active,
        List<KeyResultSnapshot> keyResults) {

    public GoalSnapshot {
        ModelValidation.requireId(id, "Goal id");
        ModelValidation.requireId(unitId, "Unit id");
        ModelValidation.requireNonNull(keyResults, "Key results");
        for (KeyResultSnapshot keyResult : keyResults) {
            ModelValidation.requireNonNull(keyResult, "Key result");
        }
        keyResults = List.copyOf(keyResults);
    }
}
