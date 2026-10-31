package ru.kkd.domain.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record RunDetails(
        String id,
        LocalDate snapshotDate,
        RunStatus status,
        String comparedToId,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        Long issuesCount,
        String errorCode) {}
