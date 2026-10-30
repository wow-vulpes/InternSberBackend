package ru.kkd.domain.model;

public record IssueDelta(Long newCount, Long persistingCount, Long resolvedCount) {}
