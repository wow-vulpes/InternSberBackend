package ru.kkd.domain.model;

public record UnitSnapshot(String id, String name, String parentId) {

    public UnitSnapshot {
        ModelValidation.requireId(id, "Unit id");
        ModelValidation.requireId(name, "Unit name");
        if (parentId != null) {
            ModelValidation.requireId(parentId, "Parent unit id");
        }
    }
}
