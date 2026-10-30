package ru.kkd.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.kkd.support.PostgresTestSupport;

@Testcontainers
class DatabaseConcurrencyTest {

    @Container static final PostgreSQLContainer<?> POSTGRES = PostgresTestSupport.newContainer();

    @BeforeAll
    static void migrate() throws Exception {
        PostgresTestSupport.migrate(POSTGRES);
    }

    @Test
    void runLockSerializesSnapshotWritingAndCompletion() throws Exception {
        // This test needs committed rows and therefore owns a database separate from schema tests.
        try (Connection writer = PostgresTestSupport.connect(POSTGRES);
                Connection finisher = PostgresTestSupport.connect(POSTGRES);
                var finishStatement = finisher.createStatement();
                var writeStatement = writer.createStatement()) {
            finishStatement.execute(
                    "INSERT INTO kkd.kkd_run(id,snapshot_date) VALUES ('LOCKED',DATE '2026-10-26')");
            writer.setAutoCommit(false);
            writeStatement.execute(
                    "INSERT INTO kkd.unit_snapshot(run_id,id,name) VALUES ('LOCKED','U1','Unit')");
            finishStatement.execute("SET lock_timeout='200ms'");
            SQLException blocked =
                    assertThrows(
                            SQLException.class,
                            () ->
                                    finishStatement.execute(
                                            "UPDATE kkd.kkd_run SET status='COMPLETED',finished_at=clock_timestamp() WHERE id='LOCKED'"));
            assertThat(blocked.getSQLState()).isEqualTo("55P03");
            writer.commit();
            finishStatement.execute(
                    "UPDATE kkd.kkd_run SET status='COMPLETED',finished_at=clock_timestamp() WHERE id='LOCKED'");
            try (var rows =
                    finishStatement.executeQuery(
                            "SELECT count(*) FROM kkd.unit_snapshot WHERE run_id='LOCKED'")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
            SQLException lateWrite =
                    assertThrows(
                            SQLException.class,
                            () ->
                                    writeStatement.execute(
                                            "INSERT INTO kkd.unit_snapshot(run_id,id,name) VALUES ('LOCKED','LATE','Late')"));
            assertThat(lateWrite.getSQLState()).isEqualTo("55000");
            writer.rollback();
        }
    }
}
