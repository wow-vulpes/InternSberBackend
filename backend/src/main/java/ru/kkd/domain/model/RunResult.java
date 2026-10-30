package ru.kkd.domain.model;

import java.time.LocalDate;

public record RunResult(
        String id,
        LocalDate snapshotDate,
        RunStatus status,
        String comparedToId,
        Long issuesCount,
        String errorCode) {}
