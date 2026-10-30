package ru.kkd.application;

import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import ru.kkd.domain.model.RunResult;
import ru.kkd.domain.model.RunStatus;

@Component
public class StartupCommands implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(StartupCommands.class);
    private final Environment environment;
    private final Clock clock;
    private final RunService runService;
    private final DemoDataInitializer demo;

    public StartupCommands(
            Environment environment, Clock clock, RunService runService, DemoDataInitializer demo) {
        this.environment = environment;
        this.clock = clock;
        this.runService = runService;
        this.demo = demo;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean demoEnabled = environment.getProperty("kkd.demo.enabled", Boolean.class, false);
        boolean runEnabled = environment.getProperty("kkd.run.enabled", Boolean.class, false);
        if (demoEnabled && runEnabled) {
            throw new IllegalArgumentException(
                    "Demo initialization and run command are mutually exclusive");
        }
        if (demoEnabled) {
            demo.initialize().forEach(this::report);
        } else if (runEnabled) {
            String date = environment.getProperty("kkd.run.date");
            RunResult result =
                    runService.run(date == null ? LocalDate.now(clock) : LocalDate.parse(date));
            report(result);
            if (result.status() != RunStatus.COMPLETED) {
                throw new IllegalStateException(
                        "Run failed: " + result.id() + " (" + result.errorCode() + ")");
            }
        }
    }

    private void report(RunResult result) {
        LOG.info(
                "KKD run: id={}, date={}, status={}, comparedTo={}, issues={}",
                result.id(),
                result.snapshotDate(),
                result.status(),
                result.comparedToId(),
                result.issuesCount());
    }
}
