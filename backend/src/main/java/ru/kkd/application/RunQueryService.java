package ru.kkd.application;

import java.util.List;
import org.springframework.stereotype.Service;
import ru.kkd.domain.model.RunDetails;
import ru.kkd.domain.model.RunResult;
import ru.kkd.domain.model.RunStatus;
import ru.kkd.persistence.RunRepository;

@Service
public class RunQueryService {

    private final RunRepository repository;

    public RunQueryService(RunRepository repository) {
        this.repository = repository;
    }

    public List<RunDetails> listRuns() {
        return repository.listRuns();
    }

    public RunResult completedRun(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("Run id must not be blank");
        }
        RunResult run =
                repository
                        .findRun(runId)
                        .orElseThrow(() -> new ResourceNotFoundException("Run", runId));
        if (run.status() != RunStatus.COMPLETED) {
            throw new RunNotCompletedException(run.id(), run.status());
        }
        return run;
    }
}
