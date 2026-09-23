package com.example.uno.integration;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class CrossServiceAuthIT {
    private static final String PASSWORD = "cross service integration password 密码";
    private static final String ORIGIN = "http://localhost:5179";
    private static final String SERVICE_KEY = "b".repeat(64);
    private static final String SERVICE_AUTH = "Basic " + Base64.getEncoder().encodeToString(("game-service:" + SERVICE_KEY).getBytes(StandardCharsets.UTF_8));
    private static final JsonMapper JSON = new JsonMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private static final List<RunningService> RUNNING = new ArrayList<>();

    @Container
    static final PostgreSQLContainer database = new PostgreSQLContainer(DockerImageName.parse(
            System.getProperty("uno.postgres.image", "postgres:17-alpine")).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("uno").withUsername("uno").withPassword("integration-admin-only")
            .withEnv("IDENTITY_DB_PASSWORD", "integration-identity-only").withEnv("GAME_DB_PASSWORD", "integration-game-only")
            .withCopyFileToContainer(MountableFile.forClasspathResource("bootstrap/10-create-service-databases.sql", 0644),
                    "/docker-entrypoint-initdb.d/10-create-service-databases.sql");
    @Container
    static final GenericContainer<?> mailpit = new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.27.8"))
            .withCommand("--smtp-disable-rdns").withExposedPorts(1025, 8025).waitingFor(Wait.forHttp("/").forPort(8025));

    private static RunningService identity;
    private static RunningService game;
    private static RunningService gateway;
    private static Map<String, String> identityEnvironment;
    private static Path runtime;

    @BeforeAll
    static void startServices() throws Exception {
        runtime = Files.createTempDirectory("uno-cross-auth-");
        identityEnvironment = new HashMap<>(Map.of(
                "IDENTITY_DB_URL", databaseUrl("uno_identity"), "IDENTITY_DB_PASSWORD", "integration-identity-only",
                "AUTH_ENABLED", "true", "AUTH_MAIL_KEY", "a".repeat(64),
                "IDENTITY_INTERNAL_AUTH_ENABLED", "true", "IDENTITY_INTERNAL_ALLOW_HTTP", "true",
                "IDENTITY_GAME_SERVICE_KEY", SERVICE_KEY, "SMTP_HOST", mailpit.getHost(),
                "SMTP_PORT", mailpit.getMappedPort(1025).toString(), "SMTP_STARTTLS", "false"));
        identity = launch("identity-service", identityEnvironment, List.of(
                "--uno.auth.mail-poll-ms=100", "--uno.auth.ip-limit=10000", "--uno.auth.account-limit=1000",
                "--spring.mail.properties.mail.smtp.localhost=localhost", "--spring.mail.properties.mail.smtp.timeout=10000"));
        game = launch("game-service", Map.of("GAME_DB_URL", databaseUrl("uno_game"), "GAME_DB_PASSWORD", "integration-game-only",
                "GAME_AUTH_ENABLED", "true", "GAME_AUTH_ALLOW_HTTP", "true", "IDENTITY_GAME_SERVICE_KEY", SERVICE_KEY,
                "IDENTITY_INTERNAL_URL", identity.base()), List.of());
        gateway = launch("gateway", Map.of("IDENTITY_URL", identity.base(), "GAME_URL", game.base(),
                "GAME_WS_URL", game.base().replace("http:", "ws:")), List.of());
    }

    @AfterAll
    static void stopServices() throws Exception {
        for (int index = RUNNING.size() - 1; index >= 0; index--) stop(RUNNING.get(index));
        if (runtime != null) {
            try (var files = Files.walk(runtime)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    @Test
    void realCredentialsResolveToSameIdentityThroughGatewayAndDirectly() throws Exception {
        JsonNode account = createAccount();
        String token = account.path("accessToken").asText();
        var introspection = call(identity.base(), "POST", "/internal/auth/introspect", Map.of("token", token, "clientType", "APP"), Map.of("Authorization", SERVICE_AUTH));
        assertEquals(200, introspection.statusCode(), introspection.body());
        assertEquals(account.path("user").path("nickname"), json(introspection).path("nickname"), introspection.body());
        for (String base : List.of(game.base(), gateway.base())) {
            var response = app(base, "GET", "/api/system/session", null, token);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(account.path("user").path("id"), json(response).path("userId"));
            assertEquals("APP", json(response).path("clientType").asText());
            assertEquals(5, json(response).size());
            assertEquals(account.path("user").path("nickname"), json(response).path("nickname"));
            assertFalse(response.body().contains(token));
            assertFalse(response.body().contains(account.path("user").path("email").asText()));
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        }
        JsonNode bootstrap = json(app(gateway.base(), "GET", "/api/system/bootstrap", null, null));
        assertTrue(bootstrap.path("features").path("authentication").asBoolean());
        assertFalse(bootstrap.path("features").path("gameplay").asBoolean());
    }

    @Test
    void forgedIdentityDoesNotOverrideCallerAndUnknownRoutesStayClosed() throws Exception {
        JsonNode player = createAccount();
        String invented = UUID.randomUUID().toString();
        for (String base : List.of(game.base(), gateway.base())) {
            var anonymous = CLIENT.send(request(base, "GET", "/api/system/session", null).header("X-User-Id", invented)
                    .header("X-Authenticated-User", "admin").header("X-UNO-Client", "APP").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, anonymous.statusCode());
            var authenticated = CLIENT.send(request(base, "GET", "/api/system/session?userId=" + invented, null)
                    .header("X-UNO-Client", "APP").header("Authorization", "Bearer " + player.path("accessToken").asText())
                    .header("X-User-Id", invented).header("X-Authenticated-User", "admin").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, authenticated.statusCode());
            assertEquals(player.path("user").path("id"), json(authenticated).path("userId"));
            var forgedRoom = app(base, "POST", "/api/rooms", Map.of("mode", "CLASSIC", "maxPlayers", 2, "userId", invented), player.path("accessToken").asText());
            assertEquals(201, forgedRoom.statusCode(), forgedRoom.body());
            assertEquals(player.path("user").path("id"), json(forgedRoom).path("hostUserId"));
            assertEquals(401, app(base, "GET", "/api/system/session", null, "x".repeat(43)).statusCode());
        }
    }

    @Test
    void refreshAndLogoutInvalidateGameAccessWithoutCaching() throws Exception {
        JsonNode original = createAccount();
        assertEquals(200, app(game.base(), "GET", "/api/system/session", null, original.path("accessToken").asText()).statusCode());
        var response = app(gateway.base(), "POST", "/api/auth/refresh", Map.of("token", original.path("refreshToken").asText()), null);
        assertEquals(200, response.statusCode());
        JsonNode next = json(response);
        assertEquals(401, app(game.base(), "GET", "/api/system/session", null, original.path("accessToken").asText()).statusCode());
        assertEquals(200, app(game.base(), "GET", "/api/system/session", null, next.path("accessToken").asText()).statusCode());
        assertEquals(204, app(gateway.base(), "POST", "/api/auth/logout", Map.of(), next.path("accessToken").asText()).statusCode());
        assertEquals(401, app(gateway.base(), "GET", "/api/system/session", null, next.path("accessToken").asText()).statusCode());
    }

    @Test
    void refreshReplayRevokesAlreadyVerifiedGameSession() throws Exception {
        JsonNode original = createAccount();
        JsonNode next = json(app(gateway.base(), "POST", "/api/auth/refresh", Map.of("token", original.path("refreshToken").asText()), null));
        assertEquals(200, app(game.base(), "GET", "/api/system/session", null, next.path("accessToken").asText()).statusCode());
        assertEquals(401, app(gateway.base(), "POST", "/api/auth/refresh", Map.of("token", original.path("refreshToken").asText()), null).statusCode());
        assertEquals(401, app(game.base(), "GET", "/api/system/session", null, next.path("accessToken").asText()).statusCode());
    }

    @Test
    void resettingPasswordDisablesExistingGameCredentials() throws Exception {
        JsonNode original = createAccount();
        String email = original.path("user").path("email").asText();
        assertEquals(202, app(gateway.base(), "POST", "/api/auth/password/forgot", Map.of("email", email), null).statusCode());
        String token = mailToken(email, "UNO 重置密码");
        assertEquals(204, app(gateway.base(), "POST", "/api/auth/password/reset", Map.of("token", token, "password", PASSWORD + " changed"), null).statusCode());
        assertEquals(401, app(game.base(), "GET", "/api/system/session", null, original.path("accessToken").asText()).statusCode());
    }

    @Test
    void disabledAndExpiredAccountsCannotEnterGame() throws Exception {
        JsonNode account = createAccount();
        executeIdentitySql("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", UUID.fromString(account.path("user").path("id").asText()));
        assertEquals(401, app(game.base(), "GET", "/api/system/session", null, account.path("accessToken").asText()).statusCode());
        JsonNode expired = createAccount();
        executeIdentitySql("UPDATE sessions SET created_at = NOW() - INTERVAL '2 hours', expires_at = NOW() - INTERVAL '1 hour' WHERE account_id = ?",
                UUID.fromString(expired.path("user").path("id").asText()));
        assertEquals(401, app(gateway.base(), "GET", "/api/system/session", null, expired.path("accessToken").asText()).statusCode());
        assertEquals(401, app(gateway.base(), "GET", "/api/system/session", null, expired.path("refreshToken").asText()).statusCode());
    }

    @Test
    void webCookieWorksAcrossServicesWithoutAcceptingAppImpersonation() throws Exception {
        JsonNode account = createAccount();
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var browser = HttpClient.newBuilder().cookieHandler(cookies).build();
        JsonNode csrf = json(browser.send(request(gateway.base(), "GET", "/api/auth/csrf", null).build(), HttpResponse.BodyHandlers.ofString()));
        var login = browser.send(request(gateway.base(), "POST", "/api/auth/login", Map.of("email", account.path("user").path("email").asText(), "password", PASSWORD))
                .header("Origin", ORIGIN).header("X-CSRF-TOKEN", csrf.path("token").asText()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode(), login.body());
        String cookie = cookies.getCookieStore().getCookies().stream().filter(value -> value.getName().equals("UNO_SESSION_DEV")).findFirst().orElseThrow().getValue();
        for (String base : List.of(game.base(), gateway.base())) {
            var session = browser.send(request(base, "GET", "/api/system/session", null).header("Origin", ORIGIN).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, session.statusCode(), session.body());
            assertEquals(account.path("user").path("id"), json(session).path("userId"));
            assertEquals("WEB", json(session).path("clientType").asText());
            assertEquals(401, app(base, "GET", "/api/system/session", null, cookie).statusCode());
            assertEquals(403, browser.send(request(base, "GET", "/api/system/session", null)
                    .header("X-UNO-Client", "APP").build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403, browser.send(request(base, "GET", "/api/system/session", null)
                    .header("Origin", "https://attacker.test").build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403, browser.send(request(base, "POST", "/api/system/session", Map.of())
                    .header("Origin", ORIGIN).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        JsonNode logoutCsrf = json(browser.send(request(gateway.base(), "GET", "/api/auth/csrf", null).build(), HttpResponse.BodyHandlers.ofString()));
        assertEquals(204, browser.send(request(gateway.base(), "POST", "/api/auth/logout", Map.of()).header("Origin", ORIGIN)
                .header("X-CSRF-TOKEN", logoutCsrf.path("token").asText()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(401, CLIENT.send(request(game.base(), "GET", "/api/system/session", null).header("Cookie", "UNO_SESSION_DEV=" + cookie)
                .build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void webAndAppShareOneWaitingRoomThroughGateway() throws Exception {
        JsonNode host = createAccount();
        JsonNode guest = createAccount();
        String hostToken = host.path("accessToken").asText();
        String guestToken = guest.path("accessToken").asText();
        JsonNode room = json(app(gateway.base(), "POST", "/api/rooms", Map.of("mode", "TEAM_2V2", "maxPlayers", 4), hostToken));
        assertEquals(host.path("user").path("id"), room.path("hostUserId"));
        assertEquals(1, room.path("members").size());
        String id = room.path("id").asText();
        assertEquals(404, app(gateway.base(), "GET", "/api/rooms/" + id, null, guestToken).statusCode());

        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var browser = HttpClient.newBuilder().cookieHandler(cookies).build();
        JsonNode csrf = json(browser.send(request(gateway.base(), "GET", "/api/auth/csrf", null).build(), HttpResponse.BodyHandlers.ofString()));
        var login = browser.send(request(gateway.base(), "POST", "/api/auth/login",
                Map.of("email", guest.path("user").path("email").asText(), "password", PASSWORD))
                .header("Origin", ORIGIN).header("X-CSRF-TOKEN", csrf.path("token").asText()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode(), login.body());
        assertEquals(403, browser.send(request(gateway.base(), "POST", "/api/rooms/join", Map.of("code", room.path("code").asText()))
                .header("Origin", ORIGIN).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        JsonNode joinCsrf = json(browser.send(request(gateway.base(), "GET", "/api/auth/csrf", null).build(), HttpResponse.BodyHandlers.ofString()));
        var joined = browser.send(request(gateway.base(), "POST", "/api/rooms/join", Map.of("code", room.path("code").asText()))
                .header("Origin", ORIGIN).header("X-CSRF-TOKEN", joinCsrf.path("token").asText()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, joined.statusCode(), joined.body());
        assertEquals(2, json(joined).path("members").size());
        assertEquals(2, json(app(gateway.base(), "GET", "/api/rooms/" + id, null, hostToken)).path("members").size());
        assertEquals(409, app(gateway.base(), "POST", "/api/rooms/" + id + "/ready",
                Map.of("ready", true, "expectedVersion", room.path("version").asLong()), hostToken).statusCode());
        assertEquals(403, app(gateway.base(), "POST", "/api/rooms/" + id + "/settings",
                Map.of("maxPlayers", 4, "expectedVersion", json(joined).path("version").asLong()), guestToken).statusCode());
        assertEquals(204, app(gateway.base(), "POST", "/api/rooms/" + id + "/leave", Map.of(), hostToken).statusCode());
        assertEquals(guest.path("user").path("id"), json(app(gateway.base(), "GET", "/api/rooms/" + id, null, guestToken)).path("hostUserId"));
    }

    @Test
    void serviceEndpointRequiresDedicatedCredentialAndIsNotPubliclyRouted() throws Exception {
        JsonNode account = createAccount();
        Map<String, String> body = Map.of("token", account.path("accessToken").asText(), "clientType", "APP");
        assertEquals(401, call(identity.base(), "POST", "/internal/auth/introspect", body, Map.of()).statusCode());
        assertEquals(401, call(identity.base(), "POST", "/internal/auth/introspect", body,
                Map.of("Authorization", "Bearer " + account.path("accessToken").asText())).statusCode());
        assertEquals(401, call(identity.base(), "POST", "/internal/auth/introspect", body,
                Map.of("Authorization", "Basic " + Base64.getEncoder().encodeToString("game-service:wrong".getBytes(StandardCharsets.UTF_8)))).statusCode());
        var valid = call(identity.base(), "POST", "/internal/auth/introspect", body, Map.of("Authorization", SERVICE_AUTH));
        assertEquals(200, valid.statusCode());
        assertTrue(json(valid).path("active").asBoolean());
        assertFalse(valid.body().contains(account.path("user").path("email").asText()));
        assertEquals(403, call(identity.base(), "POST", "/internal/auth/introspect", body, Map.of("Authorization", SERVICE_AUTH, "Origin", ORIGIN)).statusCode());
        assertEquals(403, call(identity.base(), "POST", "/internal/auth/introspect", body, Map.of("Authorization", SERVICE_AUTH, "Cookie", "test=value")).statusCode());
        assertEquals(403, call(identity.base(), "GET", "/internal/auth/introspect", null, Map.of("Authorization", SERVICE_AUTH)).statusCode());
        assertEquals(413, call(identity.base(), "POST", "/internal/auth/introspect", Map.of("token", "x".repeat(1500)), Map.of("Authorization", SERVICE_AUTH)).statusCode());
        assertEquals(400, call(identity.base(), "POST", "/internal/auth/introspect", Map.of("token", "short", "clientType", "APP"), Map.of("Authorization", SERVICE_AUTH)).statusCode());
        assertEquals(404, call(gateway.base(), "POST", "/internal/auth/introspect", body, Map.of("Authorization", SERVICE_AUTH)).statusCode());
    }

    @Test
    void unavailableIdentityFailsClosedAndRecoversWithoutGameRestart() throws Exception {
        JsonNode account = createAccount();
        String token = account.path("accessToken").asText();
        assertEquals(200, app(game.base(), "GET", "/api/system/session", null, token).statusCode());
        int port = URI.create(identity.base()).getPort();
        stop(identity);
        try {
            var unavailable = app(game.base(), "GET", "/api/system/session", null, token);
            assertEquals(503, unavailable.statusCode());
            assertEquals("AUTH_UNAVAILABLE", json(unavailable).path("code").asText());
            assertFalse(unavailable.body().contains(token));
            assertFalse(unavailable.body().contains(SERVICE_KEY));
            assertFalse(unavailable.body().contains("Exception"));
            assertEquals(200, app(game.base(), "GET", "/api/system/bootstrap", null, null).statusCode());
        } finally {
            var restartEnvironment = new HashMap<>(identityEnvironment);
            restartEnvironment.put("SERVER_PORT", Integer.toString(port));
            identity = launch("identity-service", restartEnvironment, List.of("--uno.auth.mail-poll-ms=100"));
        }
        assertEquals(200, app(gateway.base(), "GET", "/api/system/session", null, token).statusCode());
    }

    private static RunningService launch(String name, Map<String, String> environment, List<String> arguments) throws Exception {
        String version = System.getProperty("uno.project.version");
        Path jar = Path.of("..", name, "target", name + "-" + version + ".jar").toAbsolutePath().normalize();
        assertTrue(Files.exists(jar), "Build all backend modules before cross-service tests: " + jar);
        String runId = name + "-" + UUID.randomUUID();
        Path copy = runtime.resolve(runId + ".jar");
        Files.copy(jar, copy);
        Path log = Path.of("target", "failsafe-reports", runId + ".log").toAbsolutePath();
        Files.createDirectories(log.getParent());
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx192m", "-jar", copy.toString()));
        command.addAll(arguments);
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().clear();
        builder.environment().putAll(Map.of("SERVER_PORT", "0", "SERVER_ADDRESS", "127.0.0.1", "AUTH_COOKIE_SECURE", "false",
                "AUTH_ALLOWED_ORIGINS", ORIGIN, "LANG", "en_US.UTF-8"));
        builder.environment().putAll(environment);
        Process process = builder.start();
        RunningService running = new RunningService(process, "", log);
        RUNNING.add(running);
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        Pattern portPattern = Pattern.compile("(?:Tomcat|Netty) started on port (\\d+)");
        while (System.nanoTime() < deadline) {
            assertTrue(process.isAlive(), name + " stopped; see " + log);
            var matcher = portPattern.matcher(Files.readString(log));
            if (matcher.find()) {
                String base = "http://127.0.0.1:" + matcher.group(1);
                try {
                    if (call(base, "GET", "/actuator/health", null, Map.of()).statusCode() == 200) return new RunningService(process, base, log);
                } catch (IOException ignored) { }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Service startup timed out; see " + log);
    }

    private static void stop(RunningService service) throws Exception {
        service.process().destroy();
        if (!service.process().waitFor(10, TimeUnit.SECONDS)) {
            service.process().destroyForcibly();
            assertTrue(service.process().waitFor(10, TimeUnit.SECONDS));
        }
    }

    private JsonNode createAccount() throws Exception {
        String email = "cross-" + UUID.randomUUID() + "@example.test";
        assertEquals(202, app(gateway.base(), "POST", "/api/auth/register", Map.of("email", email, "password", PASSWORD, "nickname", "联调玩家"), null).statusCode());
        String token = mailToken(email, "UNO 邮箱验证");
        assertEquals(204, app(gateway.base(), "POST", "/api/auth/verify-email", Map.of("token", token), null).statusCode());
        var response = app(gateway.base(), "POST", "/api/auth/login", Map.of("email", email, "password", PASSWORD), null);
        assertEquals(200, response.statusCode(), response.body());
        return json(response);
    }

    private String mailToken(String email, String subject) throws Exception {
        String base = "http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025);
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode messages = json(call(base, "GET", "/api/v1/messages", null, Map.of()));
            for (JsonNode message : messages.path("messages")) {
                if (!subject.equals(message.path("Subject").asText())) continue;
                for (JsonNode recipient : message.path("To")) {
                    if (!email.equals(recipient.path("Address").asText())) continue;
                    JsonNode detail = json(call(base, "GET", "/api/v1/message/" + message.path("ID").asText(), null, Map.of()));
                    var matcher = Pattern.compile("Token: ([A-Za-z0-9_-]{43})").matcher(detail.path("Text").asText());
                    if (matcher.find()) return matcher.group(1);
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Expected local SMTP message did not arrive");
    }

    private static String databaseUrl(String name) {
        return "jdbc:postgresql://" + database.getHost() + ":" + database.getMappedPort(5432) + "/" + name;
    }

    private void executeIdentitySql(String sql, Object parameter) throws Exception {
        try (var connection = DriverManager.getConnection(databaseUrl("uno_identity"), "uno_identity", "integration-identity-only");
                var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            assertTrue(statement.executeUpdate() > 0);
        }
    }

    private static HttpRequest.Builder request(String base, String method, String path, Object body) {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15));
        if (body == null) return request.method(method, HttpRequest.BodyPublishers.noBody());
        return request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    }

    private static HttpResponse<String> call(String base, String method, String path, Object body, Map<String, String> headers) throws Exception {
        var builder = request(base, method, path, body);
        headers.forEach(builder::header);
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> app(String base, String method, String path, Object body, String token) throws Exception {
        return call(base, method, path, body, token == null ? Map.of("X-UNO-Client", "APP")
                : Map.of("X-UNO-Client", "APP", "Authorization", "Bearer " + token));
    }

    private static JsonNode json(HttpResponse<String> response) { return JSON.readTree(response.body()); }
    private record RunningService(Process process, String base, Path log) { }
}
