package ru.kkd.application;

import ru.kkd.domain.model.QualityComparison;
import ru.kkd.domain.model.RunResult;
import ru.kkd.domain.model.UnitSnapshot;

public record QualitySummary(
        RunResult run,
        RunResult comparedTo,
        UnitSnapshot unit,
        UnitSnapshot previousUnit,
        QualityComparison quality) {}
