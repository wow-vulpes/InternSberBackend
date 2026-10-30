package ru.kkd.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.Scope;
import liquibase.changelog.FastCheckService;
import liquibase.database.core.PostgresDatabase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.testcontainers.containers.PostgreSQLContainer;

public final class PostgresTestSupport {

    public static final String CHANGELOG = "db/changelog/db.changelog-master.xml";

    private PostgresTestSupport() {}

    public static PostgreSQLContainer<?> newContainer() {
        return new PostgreSQLContainer<>("postgres:17.11");
    }

    public static Connection connect(PostgreSQLContainer<?> postgres) throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    public static Liquibase liquibase(Connection connection) throws Exception {
        PostgresDatabase database = new PostgresDatabase();
        database.setConnection(new JdbcConnection(connection));
        database.setDefaultSchemaName("public");
        database.setLiquibaseSchemaName("public");
        return new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);
    }

    public static void migrate(PostgreSQLContainer<?> postgres) throws Exception {
        // Separate CLI processes have no shared fast-check cache. Reproduce that in one test JVM.
        Scope.getCurrentScope().getSingleton(FastCheckService.class).clearCache();
        try (Connection connection = connect(postgres);
                Liquibase liquibase = liquibase(connection)) {
            liquibase.update(new Contexts(), new LabelExpression());
        }
    }
}
