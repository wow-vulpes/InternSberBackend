package ru.kkd.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.kkd.support.PostgresTestSupport;

@Testcontainers
class MigrationLifecycleTest {

    // Match the application schema name to exercise PostgreSQL's "$user" search path.
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            PostgresTestSupport.newContainer().withUsername("kkd");

    @Test
    void updateRollbackAndUpdateRestoreSchemaConstraintsFunctionsAndCatalog() throws Exception {
        try (Connection connection = PostgresTestSupport.connect(POSTGRES)) {
            PostgresTestSupport.migrate(POSTGRES);
            List<String> schema = schemaSignature(connection);
            assertThat(schema)
                    .contains(
                            "table:unit", "table:goal", "table:key_result", "view:run_statistics");
            assertThat(
                            count(
                                    connection,
                                    "SELECT count(*) FROM information_schema.tables WHERE table_schema='kkd' AND table_type='BASE TABLE'"))
                    .isEqualTo(9);
            assertThat(count(connection, "SELECT count(*) FROM kkd.kkd_rule")).isEqualTo(4);
            // Include populated, protected history: rollback must work without deleting it by DML.
            try (var statement = connection.createStatement()) {
                statement.execute(
                        "INSERT INTO kkd.kkd_run(id,snapshot_date) VALUES ('HISTORY',DATE '2026-10-26')");
                statement.execute(
                        "INSERT INTO kkd.unit_snapshot(run_id,id,name) VALUES ('HISTORY','U1','Unit')");
                statement.execute(
                        "UPDATE kkd.kkd_run SET status='COMPLETED',finished_at=CURRENT_TIMESTAMP WHERE id='HISTORY'");
            }
            PostgresTestSupport.migrate(POSTGRES);
            assertThat(schemaSignature(connection)).isEqualTo(schema);
            assertThat(count(connection, "SELECT count(*) FROM kkd.kkd_rule")).isEqualTo(4);
            assertThat(count(connection, "SELECT count(*) FROM kkd.kkd_run")).isEqualTo(1);
            try (Connection rollbackConnection = PostgresTestSupport.connect(POSTGRES);
                    Liquibase liquibase = PostgresTestSupport.liquibase(rollbackConnection)) {
                liquibase.rollback("kkd-baseline", new Contexts(), new LabelExpression());
            }
            assertThat(count(connection, "SELECT count(*) FROM pg_namespace WHERE nspname='kkd'"))
                    .isZero();
            assertThat(count(connection, "SELECT count(*) FROM public.databasechangelog"))
                    .isZero(); // tagDatabase changeset is itself rolled back by Liquibase 4.31.1.
            // Each operation gets a fresh Liquibase instance, as separate CLI invocations do.
            PostgresTestSupport.migrate(POSTGRES);
            assertThat(schemaSignature(connection)).isEqualTo(schema);
            assertThat(count(connection, "SELECT count(*) FROM kkd.kkd_rule")).isEqualTo(4);
            assertThat(count(connection, "SELECT count(*) FROM kkd.kkd_run")).isZero();
            assertThat(count(connection, "SELECT count(*) FROM public.databasechangelog"))
                    .isEqualTo(5);
        }
    }

    private List<String> schemaSignature(Connection connection) throws Exception {
        String sql =
                """
                SELECT 'table:' || table_name FROM information_schema.tables
                WHERE table_schema='kkd' AND table_type='BASE TABLE'
                UNION ALL SELECT 'view:' || table_name FROM information_schema.views WHERE table_schema='kkd'
                UNION ALL SELECT 'view-definition:' || table_name || ':' || view_definition
                FROM information_schema.views WHERE table_schema='kkd'
                UNION ALL SELECT 'column:' || c.relname || '.' || a.attname || ':'
                    || format_type(a.atttypid,a.atttypmod) || ':' || a.attnotnull || ':'
                    || COALESCE(pg_get_expr(d.adbin,d.adrelid),'')
                FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
                JOIN pg_namespace n ON n.oid=c.relnamespace
                LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
                WHERE n.nspname='kkd' AND c.relkind IN ('r','v') AND a.attnum>0 AND NOT a.attisdropped
                UNION ALL SELECT 'constraint:' || conname || ':' || pg_get_constraintdef(c.oid)
                FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace WHERE n.nspname='kkd'
                UNION ALL SELECT 'function:' || p.proname || ':' || pg_get_functiondef(p.oid)
                FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='kkd'
                UNION ALL SELECT 'trigger:' || pg_get_triggerdef(t.oid)
                FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
                JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='kkd' AND NOT t.tgisinternal
                UNION ALL SELECT 'index:' || indexdef FROM pg_indexes WHERE schemaname='kkd'
                ORDER BY 1
                """;
        List<String> result = new ArrayList<>();
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                result.add(rows.getString(1));
            }
        }
        return result;
    }

    private long count(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
