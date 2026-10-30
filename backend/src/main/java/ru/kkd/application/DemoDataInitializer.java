package ru.kkd.application;

import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.kkd.domain.model.RunResult;
import ru.kkd.domain.model.RunStatus;
import ru.kkd.persistence.DemoDataRepository;
import ru.kkd.persistence.RunRepository;

@Service
public class DemoDataInitializer {

    public static final String FIRST_RUN_ID = "demo-2026-10-19";
    public static final String SECOND_RUN_ID = "demo-2026-10-26";
    private static final LocalDate FIRST_DATE = LocalDate.of(2026, 10, 19);
    private static final LocalDate SECOND_DATE = LocalDate.of(2026, 10, 26);
    private final DemoDataRepository data;
    private final RunRepository runs;
    private final RunService runService;
    private final TransactionTemplate transaction;

    public DemoDataInitializer(
            DemoDataRepository data,
            RunRepository runs,
            RunService runService,
            PlatformTransactionManager transactionManager) {
        this.data = data;
        this.runs = runs;
        this.runService = runService;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public List<RunResult> initialize() {
        return transaction.execute(
                status -> {
                    runs.lockExecution();
                    data.lockSources();
                    var first = runs.findRun(FIRST_RUN_ID);
                    var second = runs.findRun(SECOND_RUN_ID);
                    if (first.isPresent()
                            && second.isPresent()
                            && first.get().status() == RunStatus.COMPLETED
                            && second.get().status() == RunStatus.COMPLETED
                            && FIRST_DATE.equals(first.get().snapshotDate())
                            && SECOND_DATE.equals(second.get().snapshotDate())
                            && first.get().comparedToId() == null
                            && FIRST_RUN_ID.equals(second.get().comparedToId())) {
                        return List.of(first.get(), second.get());
                    }
                    if (!data.isEmpty()) {
                        throw new IllegalStateException(
                                "Demo initialization requires an empty database or a completed demo pair");
                    }
                    data.seedFirstState(FIRST_DATE);
                    RunResult firstRun = runService.runWithId(FIRST_RUN_ID, FIRST_DATE);
                    requireCompleted(firstRun);
                    data.applySecondState(SECOND_DATE);
                    RunResult secondRun = runService.runWithId(SECOND_RUN_ID, SECOND_DATE);
                    requireCompleted(secondRun);
                    return List.of(firstRun, secondRun);
                });
    }

    private void requireCompleted(RunResult result) {
        if (result.status() != RunStatus.COMPLETED) {
            throw new IllegalStateException("Demo run failed: " + result.id());
        }
    }
}
