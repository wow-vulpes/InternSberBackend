package ru.kkd.application;

import org.springframework.stereotype.Service;
import ru.kkd.domain.metric.QualityMetricsCalculator;
import ru.kkd.domain.model.QualitySnapshot;
import ru.kkd.domain.model.RunResult;
import ru.kkd.domain.model.UnitSnapshot;
import ru.kkd.persistence.SummaryRepository;

@Service
public class SummaryService {

    private final RunQueryService runs;
    private final SummaryRepository snapshots;
    private final QualityMetricsCalculator calculator;

    public SummaryService(
            RunQueryService runs,
            SummaryRepository snapshots,
            QualityMetricsCalculator calculator) {
        this.runs = runs;
        this.snapshots = snapshots;
        this.calculator = calculator;
    }

    public QualitySummary summary(String runId, String unitId) {
        requireId(runId, "Run id");
        requireId(unitId, "Unit id");
        RunResult run = runs.completedRun(runId);
        UnitSnapshot unit =
                snapshots
                        .findUnit(runId, unitId)
                        .orElseThrow(() -> new ResourceNotFoundException("Unit", unitId));
        QualitySnapshot current = snapshots.loadQualitySnapshot(runId, unitId);
        RunResult comparedTo = null;
        UnitSnapshot previousUnit = null;
        QualitySnapshot previous = null;
        if (run.comparedToId() != null) {
            comparedTo = runs.completedRun(run.comparedToId());
            previousUnit = snapshots.findUnit(comparedTo.id(), unitId).orElse(null);
            previous = snapshots.loadQualitySnapshot(comparedTo.id(), unitId);
        }
        return new QualitySummary(
                run, comparedTo, unit, previousUnit, calculator.calculate(current, previous));
    }

    private void requireId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
