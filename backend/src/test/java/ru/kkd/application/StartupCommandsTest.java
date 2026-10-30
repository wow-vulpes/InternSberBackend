package ru.kkd.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;
import ru.kkd.domain.model.RunResult;
import ru.kkd.domain.model.RunStatus;

class StartupCommandsTest {

    private final MockEnvironment environment = new MockEnvironment();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-26T23:30:00Z"), ZoneOffset.UTC);
    private final RunService service = mock(RunService.class);
    private final DemoDataInitializer demo = mock(DemoDataInitializer.class);
    private final StartupCommands commands = new StartupCommands(environment, clock, service, demo);

    @Test
    void normalStartupDoesNotModifyData() {
        run();
        verifyNoInteractions(service, demo);
    }

    @Test
    void conflictingModesFailBeforeAnyWrites() {
        environment
                .withProperty("kkd.demo.enabled", "true")
                .withProperty("kkd.run.enabled", "true");
        assertThatThrownBy(this::run).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(service, demo);
    }

    @Test
    void missingDateUsesInjectedUtcClock() {
        environment.withProperty("kkd.run.enabled", "true");
        LocalDate date = LocalDate.of(2026, 10, 26);
        when(service.run(date))
                .thenReturn(new RunResult("run", date, RunStatus.COMPLETED, null, 0L, null));
        run();
        verify(service).run(date);
        verifyNoInteractions(demo);
    }

    @Test
    void explicitDateIsPassedToRunService() {
        environment
                .withProperty("kkd.run.enabled", "true")
                .withProperty("kkd.run.date", "2026-10-19");
        LocalDate date = LocalDate.of(2026, 10, 19);
        when(service.run(date))
                .thenReturn(new RunResult("run", date, RunStatus.COMPLETED, null, 0L, null));
        run();
        verify(service).run(date);
    }

    @Test
    void invalidDateFailsBeforeCreatingRun() {
        environment
                .withProperty("kkd.run.enabled", "true")
                .withProperty("kkd.run.date", "2026-02-30");
        assertThatThrownBy(this::run).isInstanceOf(java.time.format.DateTimeParseException.class);
        verifyNoInteractions(service, demo);
    }

    @Test
    void failedRunMakesStartupCommandFail() {
        environment.withProperty("kkd.run.enabled", "true");
        LocalDate date = LocalDate.of(2026, 10, 26);
        when(service.run(date))
                .thenReturn(
                        new RunResult(
                                "run", date, RunStatus.FAILED, null, null, "RUN_EXECUTION_FAILED"));
        assertThatThrownBy(this::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RUN_EXECUTION_FAILED");
    }

    @Test
    void demoModeUsesInitializerWithoutStartingExtraRun() {
        environment.withProperty("kkd.demo.enabled", "true");
        when(demo.initialize()).thenReturn(List.of());
        run();
        verify(demo).initialize();
        verifyNoInteractions(service);
    }

    private void run() {
        commands.run(new DefaultApplicationArguments(new String[0]));
    }
}
