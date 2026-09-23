package com.example.uno.game;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class GameDatabaseIT {
    @Container
    static final PostgreSQLContainer database = new PostgreSQLContainer(DockerImageName.parse(
            System.getProperty("uno.postgres.image", "postgres:17-alpine"))
            .asCompatibleSubstituteFor("postgres"));

    @Test
    void gameServiceMigratesAndRestartsWithWaitingRooms() {
        for (int restart = 0; restart < 2; restart++) {
            try (var application = new SpringApplicationBuilder(GameApplication.class).run(
                    "--server.port=0", "--spring.datasource.url=" + database.getJdbcUrl(),
                    "--spring.datasource.username=" + database.getUsername(),
                    "--spring.datasource.password=" + database.getPassword())) {
                var jdbc = application.getBean(JdbcTemplate.class);
                assertEquals(2, jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE success AND version IN ('1', '2')", Integer.class));
                assertEquals(1, jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'game'", Integer.class));
                assertEquals(2, jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'game'", Integer.class));
                assertNull(jdbc.queryForObject("SELECT to_regclass('accounts')", String.class));
            }
        }
    }
}
