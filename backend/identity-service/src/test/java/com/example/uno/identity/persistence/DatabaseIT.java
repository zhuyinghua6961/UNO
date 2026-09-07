package com.example.uno.identity.persistence;

import com.example.uno.identity.IdentityApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class DatabaseIT {
    private static final String IDENTITY_PASSWORD = "integration-identity-only";
    private static final String GAME_PASSWORD = "integration-game-only";

    @Container
    static final PostgreSQLContainer database = new PostgreSQLContainer(DockerImageName.parse(
            System.getProperty("uno.postgres.image", "postgres:17-alpine"))
            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("uno").withUsername("uno").withPassword("integration-admin-only")
            .withEnv("IDENTITY_DB_PASSWORD", IDENTITY_PASSWORD)
            .withEnv("GAME_DB_PASSWORD", GAME_PASSWORD)
            .withCopyFileToContainer(MountableFile.forClasspathResource(
                    "bootstrap/10-create-service-databases.sql", 0644),
                    "/docker-entrypoint-initdb.d/10-create-service-databases.sql");

    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private String schema;

    @BeforeEach
    void isolateTest() {
        schema = "test_" + UUID.randomUUID().toString().replace("-", "");
        String baseUrl = url("uno_identity");
        new JdbcTemplate(new DriverManagerDataSource(baseUrl, "uno_identity", IDENTITY_PASSWORD))
                .execute("CREATE SCHEMA " + schema);
        source = new DriverManagerDataSource(baseUrl + "?currentSchema=" + schema,
                "uno_identity", IDENTITY_PASSWORD);
        jdbc = new JdbcTemplate(source);
    }

    @Test
    void emptyDatabaseMigratesAndRepeatPreservesAccounts() {
        assertEquals(2, migrations().migrate().migrationsExecuted);
        JdbcAccountStore store = new JdbcAccountStore(JdbcClient.create(source));
        Account account = store.create(" Player@Example.COM ", "test-encoded-password", "玩家");
        assertEquals("player@example.com", account.email());
        assertEquals("PENDING", account.status());
        assertNotNull(account.createdAt());
        assertEquals(0, migrations().migrate().migrationsExecuted);
        assertEquals(account, store.findById(account.id()).orElseThrow());
        assertFalse(account.toString().contains("test-encoded-password"));
        assertTrue(store.findById(UUID.randomUUID()).isEmpty());
    }

    @Test
    void upgradesV1WithoutLosingData() {
        Flyway.configure().dataSource(source).schemas(schema).target("1").load().migrate();
        Account account = new JdbcAccountStore(JdbcClient.create(source))
                .create("upgrade@example.com", "test-hash", "升级测试");
        assertEquals(1, migrations().migrate().migrationsExecuted);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM accounts WHERE id = ?", Integer.class, account.id()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sessions", Integer.class));
    }

    @Test
    void uniquenessAndTransactionRollbackAreEnforced() {
        migrations().migrate();
        JdbcAccountStore store = new JdbcAccountStore(JdbcClient.create(source));
        store.create("unique@example.com", "test-hash", "原账号");
        assertThrows(DataIntegrityViolationException.class,
                () -> store.create(" UNIQUE@EXAMPLE.COM ", "test-hash", "重复"));
        TransactionTemplate transaction = new TransactionTemplate(new JdbcTransactionManager(source));
        assertThrows(DataIntegrityViolationException.class, () -> transaction.executeWithoutResult(status -> {
            store.create("rolledback@example.com", "test-hash", "不能留下");
            store.create("unique@example.com", "test-hash", "冲突");
        }));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM accounts", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE accounts SET email = 'UPPER@example.com'",
            "UPDATE accounts SET nickname = ' '",
            "UPDATE accounts SET password_hash = ''",
            "UPDATE accounts SET status = 'UNKNOWN'",
            "UPDATE accounts SET updated_at = created_at - INTERVAL '1 second'"
    })
    void accountConstraintsRejectInvalidRows(String statement) {
        migrations().migrate();
        new JdbcAccountStore(JdbcClient.create(source)).create("valid@example.com", "test-hash", "测试");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(statement));
    }

    @Test
    void sessionAndTokenConstraintsEnforceReferencesExpiryAndDigests() {
        migrations().migrate();
        Account account = new JdbcAccountStore(JdbcClient.create(source))
                .create("tokens@example.com", "test-hash", "令牌");
        String sessionSql = "INSERT INTO sessions (id, account_id, token_digest, client_type, expires_at) "
                + "VALUES (?, ?, ?, 'WEB', CURRENT_TIMESTAMP + INTERVAL '1 hour')";
        jdbc.update(sessionSql, UUID.randomUUID(), account.id(), "a".repeat(64));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(sessionSql, UUID.randomUUID(), account.id(), "a".repeat(64)));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(sessionSql, UUID.randomUUID(), UUID.randomUUID(), "b".repeat(64)));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(sessionSql, UUID.randomUUID(), account.id(), "raw-token"));
        jdbc.update("INSERT INTO account_tokens (id, account_id, purpose, token_digest, expires_at) "
                + "VALUES (?, ?, 'VERIFY_EMAIL', ?, CURRENT_TIMESTAMP + INTERVAL '1 hour')",
                UUID.randomUUID(), account.id(), "c".repeat(64));
        for (String table : new String[]{"sessions", "account_tokens"}) {
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("UPDATE " + table + " SET expires_at = created_at"));
        }
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE account_tokens SET purpose = 'UNKNOWN'"));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE account_tokens SET consumed_at = created_at - INTERVAL '1 second'"));
        jdbc.update("DELETE FROM accounts WHERE id = ?", account.id());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sessions", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM account_tokens", Integer.class));
    }

    @Test
    void failedMigrationRollsBackDdlAndDoesNotRecordSuccess() {
        migrations().migrate();
        assertThrows(FlywayException.class, () -> Flyway.configure().dataSource(source).schemas(schema)
                .locations("classpath:db/migration", "classpath:db/failing").load().migrate());
        assertNull(jdbc.queryForObject("SELECT to_regclass('must_not_survive')", String.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL", Integer.class));
        assertEquals(0, migrations().migrate().migrationsExecuted);
    }

    @Test
    void serviceCredentialsCannotCrossDatabasesOrCreateRoles() throws Exception {
        assertThrows(java.sql.SQLException.class,
                () -> DriverManager.getConnection(url("uno_game"), "uno_identity", IDENTITY_PASSWORD));
        assertThrows(java.sql.SQLException.class,
                () -> DriverManager.getConnection(url("uno_identity"), "uno_game", GAME_PASSWORD));
        assertFalse(jdbc.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class));
        var denied = assertThrows(DataAccessException.class, () -> jdbc.execute("CREATE ROLE forbidden_role"));
        assertEquals("42501", ((java.sql.SQLException) denied.getMostSpecificCause()).getSQLState());
        try (var connection = DriverManager.getConnection(url("uno_game"), "uno_game", GAME_PASSWORD)) {
            assertTrue(connection.isValid(2));
        }
    }

    @Test
    void backupRestoresIntoAnIsolatedDatabase() throws Exception {
        migrations().migrate();
        Account account = new JdbcAccountStore(JdbcClient.create(source))
                .create("backup@example.com", "test-hash", "恢复演练");
        String restoredDatabase = "restore_" + UUID.randomUUID().toString().replace("-", "");
        String backup = "/tmp/" + restoredDatabase + ".dump";
        assertEquals(0, database.execInContainer("pg_dump", "-U", "uno", "-d", "uno_identity",
                "-Fc", "--schema=" + schema, "--file=" + backup).getExitCode());
        assertEquals(0, database.execInContainer("createdb", "-U", "uno", "--owner=uno_identity",
                restoredDatabase).getExitCode());
        assertEquals(0, database.execInContainer("psql", "-U", "uno", "-d", "uno", "-v", "ON_ERROR_STOP=1",
                "-c", "REVOKE ALL ON DATABASE " + restoredDatabase + " FROM PUBLIC").getExitCode());
        var restore = database.execInContainer("pg_restore", "-U", "uno", "-d", restoredDatabase,
                "--exit-on-error", "--single-transaction", "--no-owner", "--role=uno_identity", backup);
        assertEquals(0, restore.getExitCode(), restore.getStderr());
        var restoredSource = new DriverManagerDataSource(url(restoredDatabase) + "?currentSchema=" + schema,
                "uno_identity", IDENTITY_PASSWORD);
        assertEquals(account, new JdbcAccountStore(JdbcClient.create(restoredSource)).findById(account.id()).orElseThrow());
        assertEquals(0, Flyway.configure().dataSource(restoredSource).schemas(schema).load().migrate().migrationsExecuted);
    }

    @Test
    void realServiceRestartPreservesAccountAndHealthIsRedacted() throws Exception {
        UUID accountId;
        try (var application = startApplication()) {
            accountId = application.getBean(JdbcAccountStore.class)
                    .create("restart@example.com", "test-secret-hash", "重启测试").id();
            assertHealth(application, "/actuator/health/readiness", 200, "UP");
        }
        try (var application = startApplication()) {
            assertTrue(application.getBean(JdbcAccountStore.class).findById(accountId).isPresent());
            assertHealth(application, "/actuator/health/liveness", 200, "UP");
            var pool = application.getBean(com.zaxxer.hikari.HikariDataSource.class);
            pool.close();
            assertHealth(application, "/actuator/health/readiness", 503, "DOWN");
            assertHealth(application, "/actuator/health/liveness", 200, "UP");
        }
    }

    private Flyway migrations() {
        return Flyway.configure().dataSource(source).schemas(schema).load();
    }

    private ConfigurableApplicationContext startApplication() {
        return new SpringApplicationBuilder(IdentityApplication.class).run(
                "--server.port=0", "--spring.datasource.url=" + source.getUrl(),
                "--spring.datasource.username=uno_identity", "--spring.datasource.password=" + IDENTITY_PASSWORD,
                "--spring.flyway.schemas=" + schema);
    }

    private void assertHealth(ConfigurableApplicationContext application, String path, int status, String health) throws Exception {
        int port = ((WebServerApplicationContext) application).getWebServer().getPort();
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode());
        assertEquals("{\"status\":\"" + health + "\"}", response.body());
    }

    private static String url(String name) {
        return "jdbc:postgresql://" + database.getHost() + ":" + database.getMappedPort(5432) + "/" + name;
    }
}
