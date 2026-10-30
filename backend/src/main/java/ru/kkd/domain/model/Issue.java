package ru.kkd.domain.model;

public record Issue(
        RuleCode ruleCode, ObjectType objectType, String objectId, String goalId, String unitId) {

    public Issue {
        ModelValidation.requireNonNull(ruleCode, "Rule code");
        ModelValidation.requireNonNull(objectType, "Object type");
        ModelValidation.requireId(objectId, "Object id");
        ModelValidation.requireId(goalId, "Goal id");
        ModelValidation.requireId(unitId, "Unit id");
    }

    public Severity severity() {
        return ruleCode.severity();
    }
}
