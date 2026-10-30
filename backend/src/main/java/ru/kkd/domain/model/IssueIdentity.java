package ru.kkd.domain.model;

public record IssueIdentity(RuleCode ruleCode, ObjectType objectType, String objectId) {

    public IssueIdentity {
        ModelValidation.requireNonNull(ruleCode, "Rule code");
        ModelValidation.requireNonNull(objectType, "Object type");
        ModelValidation.requireId(objectId, "Object id");
    }
}
