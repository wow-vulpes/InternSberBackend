package ru.kkd.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.RunResult;
import ru.kkd.domain.rule.DataQualityChecker;
import ru.kkd.persistence.RunRepository;

@Service
public class RunService {

    private static final Logger LOG = LoggerFactory.getLogger(RunService.class);
    private final RunRepository repository;
    private final DataQualityChecker checker;
    private final TransactionTemplate transaction;

    public RunService(
            RunRepository repository,
            DataQualityChecker checker,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.checker = checker;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public RunResult run(LocalDate snapshotDate) {
        return runWithId(UUID.randomUUID().toString(), snapshotDate);
    }

    RunResult runWithId(String runId, LocalDate snapshotDate) {
        if (snapshotDate == null) {
            throw new IllegalArgumentException("Snapshot date must not be null");
        }
        return transaction.execute(
                status -> {
                    repository.lockExecution();
                    String previousId = repository.previousCompletedRun(snapshotDate);
                    repository.createRun(runId, snapshotDate, previousId);
                    Object savepoint = status.createSavepoint();
                    try {
                        repository.captureSnapshot(runId);
                        List<Issue> issues =
                                checker.check(repository.loadGoals(runId), snapshotDate);
                        repository.saveIssues(runId, issues);
                        repository.complete(runId);
                    } catch (RuntimeException exception) {
                        status.rollbackToSavepoint(savepoint);
                        repository.fail(runId);
                        LOG.warn("Run {} failed: {}", runId, exception.getClass().getSimpleName());
                    } finally {
                        status.releaseSavepoint(savepoint);
                    }
                    return repository.findRun(runId).orElseThrow();
                });
    }
}
