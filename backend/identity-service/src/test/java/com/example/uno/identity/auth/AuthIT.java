package com.example.uno.identity.auth;

import com.example.uno.identity.IdentityApplication;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class AuthIT {
    private static final String PASSWORD = "correct horse battery staple 密码";
    private static final String ORIGIN = "http://localhost:5179";
    private static final String SERVICE_KEY = "d".repeat(64);
    private static final JsonMapper JSON = new JsonMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Container
    static final PostgreSQLContainer database = new PostgreSQLContainer(DockerImageName.parse(
            System.getProperty("uno.postgres.image", "postgres:17-alpine")).asCompatibleSubstituteFor("postgres"));
    @Container
    static final GenericContainer<?> smtp = new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.27.8"))
            .withCommand("--smtp-disable-rdns").withExposedPorts(1025, 8025).waitingFor(Wait.forHttp("/").forPort(8025));

    private static ConfigurableApplicationContext application;
    private static String base;
    private JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        application = new SpringApplicationBuilder(IdentityApplication.class).run(
                "--server.port=0", "--server.address=127.0.0.1", "--spring.datasource.url=" + database.getJdbcUrl(),
                "--spring.datasource.username=" + database.getUsername(), "--spring.datasource.password=" + database.getPassword(),
                "--uno.auth.enabled=true", "--uno.auth.secure-cookies=false", "--uno.auth.allowed-origins=" + ORIGIN,
                "--uno.auth.mail-key=" + "a".repeat(64), "--uno.auth.mail-dispatch-enabled=false",
                "--uno.auth.ip-limit=10000", "--uno.auth.account-limit=1000",
                "--uno.internal-auth.enabled=true", "--uno.internal-auth.game-service-key=" + SERVICE_KEY,
                "--uno.internal-auth.allow-insecure-http=true",
                "--spring.mail.host=" + smtp.getHost(), "--spring.mail.port=" + smtp.getMappedPort(1025),
                "--spring.mail.properties.mail.smtp.connectiontimeout=10000", "--spring.mail.properties.mail.smtp.timeout=10000",
                "--spring.mail.properties.mail.smtp.writetimeout=10000", "--spring.mail.properties.mail.smtp.localhost=localhost",
                "--spring.mail.properties.mail.smtp.starttls.enable=false", "--spring.mail.properties.mail.smtp.starttls.required=false");
        base = "http://127.0.0.1:" + ((WebServerApplicationContext) application).getWebServer().getPort();
    }

    @AfterAll
    static void stop() { if (application != null) application.close(); }

    @BeforeEach
    void clearIsolatedDatabase() {
        jdbc = application.getBean(JdbcTemplate.class);
        jdbc.execute("TRUNCATE accounts, mail_outbox, auth_rate_limits CASCADE");
    }

    @Test
    void twoRealAccountsHaveIndependentIdentitiesAndNoPlaintextSecrets() throws Exception {
        String firstEmail = "first@example.test";
        String secondEmail = "second@example.test";
        createVerified(firstEmail);
        createVerified(secondEmail);
        JsonNode first = login(firstEmail);
        JsonNode second = login(secondEmail);
        assertNotEquals(first.path("user").path("id"), second.path("user").path("id"));
        assertEquals(firstEmail, json(app("GET", "/api/users/me", null, first.path("accessToken").asText())).path("email").asText());
        assertEquals(secondEmail, json(app("GET", "/api/users/me", null, second.path("accessToken").asText())).path("email").asText());
        String storage = jdbc.queryForList("SELECT password_hash FROM accounts").toString()
                + jdbc.queryForList("SELECT token_digest FROM sessions").toString()
                + jdbc.queryForList("SELECT token_digest FROM refresh_tokens").toString()
                + jdbc.queryForList("SELECT encrypted_body FROM mail_outbox").toString();
        assertFalse(storage.contains(PASSWORD));
        assertFalse(storage.contains(first.path("accessToken").asText()));
        assertFalse(storage.contains(first.path("refreshToken").asText()));
        assertTrue(jdbc.queryForObject("SELECT bool_and(password_hash LIKE '{pbkdf2-sha256}%') FROM accounts", Boolean.class));
        assertEquals(401, app("GET", "/api/users/me", null, first.path("refreshToken").asText()).statusCode());
    }

    @Test
    void nicknameUpdateUsesCurrentIdentityAndIsVisibleAcrossExistingSessions() throws Exception {
        createVerified("profile@example.test");
        createVerified("other@example.test");
        JsonNode first = login("profile@example.test");
        JsonNode second = login("profile@example.test");
        JsonNode other = login("other@example.test");
        String firstToken = first.path("accessToken").asText();
        var changed = app("POST", "/api/users/me/profile", Map.of("nickname", "  新昵称  "), firstToken);
        assertEquals(200, changed.statusCode(), changed.body());
        assertEquals("新昵称", json(changed).path("nickname").asText());
        assertEquals(first.path("user").path("id"), json(changed).path("id"));
        assertEquals("新昵称", json(app("GET", "/api/users/me", null,
                second.path("accessToken").asText())).path("nickname").asText());
        assertEquals("玩家", json(app("GET", "/api/users/me", null,
                other.path("accessToken").asText())).path("nickname").asText());
        assertEquals(400, app("POST", "/api/users/me/profile", Map.of("nickname", " "), firstToken).statusCode());
        assertEquals(400, app("POST", "/api/users/me/profile", Map.of("nickname", "x".repeat(41)), firstToken).statusCode());
        assertEquals(400, app("POST", "/api/users/me/profile", Map.of("nickname", "line\nbreak"), firstToken).statusCode());
        assertEquals(401, app("POST", "/api/users/me/profile", Map.of("nickname", "偷改"), null).statusCode());
        assertEquals("新昵称", json(app("GET", "/api/users/me", null, firstToken)).path("nickname").asText());
    }

    @Test
    void pendingDuplicateCannotActivateAnOldPasswordOrRevealExistingAccount() throws Exception {
        String email = "pending@example.test";
        var first = register(email, PASSWORD);
        String oldToken = latestToken(email);
        assertEquals(401, app("POST", "/api/auth/login", credentials(email, PASSWORD), null).statusCode());
        String replacement = PASSWORD + " replacement";
        var duplicate = register(email.toUpperCase(), replacement);
        assertEquals(first.statusCode(), duplicate.statusCode());
        assertEquals(first.body(), duplicate.body());
        assertEquals(400, app("POST", "/api/auth/verify-email", Map.of("token", oldToken), null).statusCode());
        assertEquals(204, app("POST", "/api/auth/verify-email", Map.of("token", latestToken(email)), null).statusCode());
        assertEquals(401, app("POST", "/api/auth/login", credentials(email, PASSWORD), null).statusCode());
        assertEquals(200, app("POST", "/api/auth/login", credentials(email, replacement), null).statusCode());
        assertEquals(202, register(email, PASSWORD).statusCode());
        assertEquals(200, app("POST", "/api/auth/login", credentials(email, replacement), null).statusCode());
    }

    @Test
    void verificationTokensAreSingleUseAndExpire() throws Exception {
        String email = "verify@example.test";
        register(email, PASSWORD);
        String token = latestToken(email);
        jdbc.update("UPDATE account_tokens SET created_at = NOW() - INTERVAL '2 hours', expires_at = NOW() - INTERVAL '1 hour'");
        assertEquals(400, app("POST", "/api/auth/verify-email", Map.of("token", token), null).statusCode());
        assertEquals(202, app("POST", "/api/auth/verification/request", Map.of("email", email), null).statusCode());
        String fresh = latestToken(email);
        assertEquals(204, app("POST", "/api/auth/verify-email", Map.of("token", fresh), null).statusCode());
        assertEquals(400, app("POST", "/api/auth/verify-email", Map.of("token", fresh), null).statusCode());
    }

    @Test
    void unknownWrongAndDisabledLoginsShareTheSameFailure() throws Exception {
        String email = "disabled@example.test";
        createVerified(email);
        JsonNode session = login(email);
        assertEquals(403, app("POST", "/api/users/disable", Map.of("userId", session.path("user").path("id").asText()), session.path("accessToken").asText()).statusCode());
        var wrong = app("POST", "/api/auth/login", credentials(email, PASSWORD + "wrong"), null);
        var unknown = app("POST", "/api/auth/login", credentials("missing@example.test", PASSWORD), null);
        application.getBean(AuthService.class).disableAccount(UUID.fromString(session.path("user").path("id").asText()));
        var disabled = app("POST", "/api/auth/login", credentials(email, PASSWORD), null);
        for (var response : new HttpResponse[]{wrong, unknown, disabled}) assertEquals(401, response.statusCode());
        assertEquals(json(wrong).path("code"), json(unknown).path("code"));
        assertEquals(json(wrong).path("message"), json(disabled).path("message"));
        assertEquals(401, app("GET", "/api/users/me", null, session.path("accessToken").asText()).statusCode());
        assertEquals(401, app("POST", "/api/auth/refresh", Map.of("token", session.path("refreshToken").asText()), null).statusCode());
    }

    @Test
    void appRefreshRotatesBothTokensAndReplayRevokesTheFamily() throws Exception {
        String email = "refresh@example.test";
        createVerified(email);
        JsonNode original = login(email);
        JsonNode renewed = json(app("POST", "/api/auth/refresh", Map.of("token", original.path("refreshToken").asText()), null));
        assertNotEquals(original.path("accessToken"), renewed.path("accessToken"));
        assertNotEquals(original.path("refreshToken"), renewed.path("refreshToken"));
        assertEquals(401, app("GET", "/api/users/me", null, original.path("accessToken").asText()).statusCode());
        assertEquals(200, app("GET", "/api/users/me", null, renewed.path("accessToken").asText()).statusCode());
        assertEquals(401, app("POST", "/api/auth/refresh", Map.of("token", original.path("refreshToken").asText()), null).statusCode());
        assertEquals(401, app("GET", "/api/users/me", null, renewed.path("accessToken").asText()).statusCode());
        assertEquals(401, app("POST", "/api/auth/refresh", Map.of("token", renewed.path("refreshToken").asText()), null).statusCode());
    }

    @Test
    void concurrentRefreshHasOneWinnerAndRejectsReplay() throws Exception {
        String email = "race@example.test";
        createVerified(email);
        JsonNode initial = login(email);
        var request = request("POST", "/api/auth/refresh", Map.of("token", initial.path("refreshToken").asText()))
                .header("X-UNO-Client", "APP").build();
        var first = CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var second = CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(first, second).join();
        assertEquals(java.util.Set.of(200, 401), java.util.Set.of(first.join().statusCode(), second.join().statusCode()));
        JsonNode issued = json(first.join().statusCode() == 200 ? first.join() : second.join());
        assertEquals(401, app("GET", "/api/users/me", null, issued.path("accessToken").asText()).statusCode());
    }

    @Test
    void logoutAndExpiredCredentialsCannotBeUsed() throws Exception {
        String email = "expiry@example.test";
        createVerified(email);
        JsonNode session = login(email);
        jdbc.update("UPDATE sessions SET created_at = NOW() - INTERVAL '2 hours', expires_at = NOW() - INTERVAL '1 hour'");
        assertEquals(401, app("GET", "/api/users/me", null, session.path("accessToken").asText()).statusCode());
        JsonNode refreshed = json(app("POST", "/api/auth/refresh", Map.of("token", session.path("refreshToken").asText()), null));
        assertEquals(204, app("POST", "/api/auth/logout", null, refreshed.path("accessToken").asText()).statusCode());
        assertEquals(401, app("GET", "/api/users/me", null, refreshed.path("accessToken").asText()).statusCode());
        assertEquals(401, app("POST", "/api/auth/refresh", Map.of("token", refreshed.path("refreshToken").asText()), null).statusCode());
        JsonNode another = login(email);
        jdbc.update("UPDATE refresh_tokens SET consumed_at = NULL, created_at = NOW() - INTERVAL '2 hours', expires_at = NOW() - INTERVAL '1 hour'");
        assertEquals(401, app("POST", "/api/auth/refresh", Map.of("token", another.path("refreshToken").asText()), null).statusCode());
    }

    @Test
    void internalSessionStatusTracksLogoutExpiryAndAccountDisable() throws Exception {
        createVerified("voice-one@example.test");
        createVerified("voice-two@example.test");
        JsonNode first = login("voice-one@example.test");
        JsonNode second = login("voice-two@example.test");
        UUID firstId = jdbc.queryForObject("SELECT id FROM sessions WHERE token_digest = ?", UUID.class,
                Secrets.digest(first.path("accessToken").asText()));
        UUID secondId = jdbc.queryForObject("SELECT id FROM sessions WHERE token_digest = ?", UUID.class,
                Secrets.digest(second.path("accessToken").asText()));
        UUID unknown = UUID.randomUUID();
        JsonNode initial = json(internalSessions(firstId, secondId, unknown));
        assertEquals(2, initial.path("activeSessionIds").size());
        assertTrue(initial.path("activeSessionIds").toString().contains(firstId.toString()));
        assertTrue(initial.path("activeSessionIds").toString().contains(secondId.toString()));
        assertEquals(204, app("POST", "/api/auth/logout", null, first.path("accessToken").asText()).statusCode());
        assertEquals(secondId.toString(), json(internalSessions(firstId, secondId)).path("activeSessionIds").get(0).asText());
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = (SELECT account_id FROM sessions WHERE id = ?)", secondId);
        assertEquals(0, json(internalSessions(firstId, secondId)).path("activeSessionIds").size());
        assertEquals(401, CLIENT.send(request("POST", "/internal/auth/sessions/active",
                Map.of("sessionIds", java.util.List.of(firstId))).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void passwordResetIsSingleUseAndRevokesEveryOldSession() throws Exception {
        String email = "reset@example.test";
        createVerified(email);
        JsonNode first = login(email);
        JsonNode second = login(email);
        var existing = app("POST", "/api/auth/password/forgot", Map.of("email", email), null);
        var unknown = app("POST", "/api/auth/password/forgot", Map.of("email", "unknown@example.test"), null);
        assertEquals(existing.statusCode(), unknown.statusCode());
        assertEquals(existing.body(), unknown.body());
        String token = latestToken(email);
        String newPassword = PASSWORD + " reset";
        assertEquals(204, app("POST", "/api/auth/password/reset", Map.of("token", token, "password", newPassword), null).statusCode());
        assertEquals(400, app("POST", "/api/auth/password/reset", Map.of("token", token, "password", newPassword), null).statusCode());
        assertEquals(401, app("GET", "/api/users/me", null, first.path("accessToken").asText()).statusCode());
        assertEquals(401, app("GET", "/api/users/me", null, second.path("accessToken").asText()).statusCode());
        assertEquals(401, app("POST", "/api/auth/refresh", Map.of("token", first.path("refreshToken").asText()), null).statusCode());
        assertEquals(401, app("POST", "/api/auth/login", credentials(email, PASSWORD), null).statusCode());
        assertEquals(200, app("POST", "/api/auth/login", credentials(email, newPassword), null).statusCode());
    }

    @Test
    void webLoginRequiresOriginAndCsrfAndReturnsOnlyHttpOnlySession() throws Exception {
        String email = "browser@example.test";
        createVerified(email);
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var browser = HttpClient.newBuilder().cookieHandler(cookies).build();
        assertEquals(403, browser.send(request("POST", "/api/auth/login", credentials(email, PASSWORD))
                .header("Origin", ORIGIN).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        String csrf = json(browser.send(request("GET", "/api/auth/csrf", null).build(), HttpResponse.BodyHandlers.ofString())).path("token").asText();
        var badOrigin = browser.send(request("POST", "/api/auth/login", credentials(email, PASSWORD))
                .header("Origin", "https://attacker.test").header("X-CSRF-TOKEN", csrf).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, badOrigin.statusCode());
        var loggedIn = browser.send(request("POST", "/api/auth/login", credentials(email, PASSWORD))
                .header("Origin", ORIGIN).header("X-CSRF-TOKEN", csrf).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, loggedIn.statusCode(), loggedIn.body());
        assertFalse(json(loggedIn).has("accessToken"));
        assertFalse(json(loggedIn).has("refreshToken"));
        String cookieHeader = loggedIn.headers().allValues("set-cookie").stream().filter(value -> value.startsWith("UNO_SESSION_DEV=")).findFirst().orElseThrow();
        assertTrue(cookieHeader.contains("HttpOnly"));
        assertTrue(cookieHeader.contains("SameSite=Strict"));
        assertTrue(cookieHeader.contains("Path=/"));
        assertFalse(cookieHeader.contains("Domain="));
        String cookieToken = cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("UNO_SESSION_DEV")).findFirst().orElseThrow().getValue();
        assertEquals(200, browser.send(request("GET", "/api/users/me", null).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403, browser.send(request("POST", "/api/users/me/profile", Map.of("nickname", "浏览器"))
                .header("Origin", ORIGIN).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        String profileCsrf = json(browser.send(request("GET", "/api/auth/csrf", null).build(),
                HttpResponse.BodyHandlers.ofString())).path("token").asText();
        var updated = browser.send(request("POST", "/api/users/me/profile", Map.of("nickname", "浏览器"))
                .header("Origin", ORIGIN).header("X-CSRF-TOKEN", profileCsrf).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, updated.statusCode(), updated.body());
        assertEquals("浏览器", json(updated).path("nickname").asText());
        assertEquals(401, app("GET", "/api/users/me", null, cookieToken).statusCode());
        assertEquals(403, browser.send(request("POST", "/api/auth/logout", null).header("Origin", ORIGIN).header("X-UNO-Client", "APP").build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        String rotated = json(browser.send(request("GET", "/api/auth/csrf", null).build(), HttpResponse.BodyHandlers.ofString())).path("token").asText();
        assertEquals(204, browser.send(request("POST", "/api/auth/logout", null).header("Origin", ORIGIN).header("X-CSRF-TOKEN", rotated).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(401, browser.send(request("GET", "/api/users/me", null).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void forgedHeadersAndMixedTransportDoNotCreateAnIdentity() throws Exception {
        var fake = request("GET", "/api/users/me", null).header("X-UNO-Client", "APP")
                .header("X-User-Id", UUID.randomUUID().toString()).header("X-Authenticated-User", "admin").build();
        assertEquals(401, CLIENT.send(fake, HttpResponse.BodyHandlers.ofString()).statusCode());
        var browserPretendingApp = request("POST", "/api/auth/login", credentials("unknown@example.test", PASSWORD))
                .header("X-UNO-Client", "APP").header("Origin", ORIGIN).build();
        assertEquals(403, CLIENT.send(browserPretendingApp, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403, CLIENT.send(request("GET", "/api/users/me", null).header("X-UNO-Client", "APP")
                .header("Cookie", "something=present").build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(401, CLIENT.send(request("GET", "/actuator/env", null).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(401, CLIENT.send(request("GET", "/internal/auth/introspect", null).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void limitsSurviveFailuresAndForwardedHeadersCannotBypassThem() throws Exception {
        String email = "limited@example.test";
        jdbc.update("INSERT INTO auth_rate_limits (bucket_key, window_started_at, attempts) VALUES (?, NOW(), 1000)", Secrets.digest("login:" + email));
        var accountLimited = app("POST", "/api/auth/login", credentials(email.toUpperCase(), PASSWORD), null);
        assertEquals(429, accountLimited.statusCode());
        assertEquals("900", accountLimited.headers().firstValue("Retry-After").orElseThrow());
        jdbc.update("UPDATE auth_rate_limits SET attempts = 10000 WHERE bucket_key = ?", Secrets.digest("ip:127.0.0.1"));
        var spoofed = CLIENT.send(request("POST", "/api/auth/login", credentials("different@example.test", PASSWORD))
                .header("X-UNO-Client", "APP").header("X-Forwarded-For", "203.0.113.10").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(429, spoofed.statusCode());
        jdbc.update("UPDATE auth_rate_limits SET window_started_at = NOW() - INTERVAL '16 minutes'");
        assertEquals(401, app("POST", "/api/auth/login", credentials("different@example.test", PASSWORD), null).statusCode());
    }

    @Test
    void invalidAndOversizedBodiesDoNotLeakDetails() throws Exception {
        assertEquals(400, register("not-an-email", PASSWORD).statusCode());
        assertEquals(400, register("short@example.test", "short").statusCode());
        var malformed = CLIENT.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                .header("X-UNO-Client", "APP").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{invalid")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, malformed.statusCode());
        assertFalse(malformed.body().contains("Exception"));
        var oversized = app("POST", "/api/auth/register", Map.of("email", "oversized@example.test", "password", "x".repeat(17000), "nickname", "玩家"), null);
        assertEquals(413, oversized.statusCode());
        assertTrue(json(oversized).has("requestId"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM accounts", Integer.class));
    }

    @Test
    void encryptedOutboxRetriesRealSmtpAndErasesDeliveredBody() throws Exception {
        String email = "smtp-" + UUID.randomUUID() + "@example.test";
        assertEquals(202, register(email, PASSWORD).statusCode());
        String token = latestToken(email);
        assertFalse(jdbc.queryForObject("SELECT encrypted_body FROM mail_outbox WHERE recipient = ?", String.class, email).contains(token));
        JavaMailSenderImpl sender = application.getBean(JavaMailSenderImpl.class);
        int actualPort = sender.getPort();
        try {
            sender.setPort(1);
            application.getBean(MailOutbox.class).deliverBatch();
            assertEquals(1, jdbc.queryForObject("SELECT attempts FROM mail_outbox WHERE recipient = ?", Integer.class, email));
            assertEquals("DELIVERY_FAILED", jdbc.queryForObject("SELECT last_error FROM mail_outbox WHERE recipient = ?", String.class, email));
        } finally {
            sender.setPort(actualPort);
        }
        sender.testConnection();
        jdbc.update("UPDATE mail_outbox SET next_attempt_at = NOW() - INTERVAL '1 minute' WHERE recipient = ?", email);
        application.getBean(MailOutbox.class).deliverBatch();
        assertNull(jdbc.queryForObject("SELECT encrypted_body FROM mail_outbox WHERE recipient = ?", String.class, email));
        assertNotNull(jdbc.queryForObject("SELECT sent_at FROM mail_outbox WHERE recipient = ?", java.sql.Timestamp.class, email));
        JsonNode messages = mailpit("/api/v1/messages");
        boolean delivered = false;
        for (JsonNode message : messages.path("messages")) {
            JsonNode detail = mailpit("/api/v1/message/" + message.path("ID").asText());
            if (detail.path("Text").asText().contains(token)) delivered = true;
        }
        assertTrue(delivered, "Mailpit must receive the real SMTP message containing the token");
        assertEquals(204, app("POST", "/api/auth/verify-email", Map.of("token", token), null).statusCode());
    }

    @Test
    void issuedSessionSurvivesApplicationRestart() throws Exception {
        createVerified("restart@example.test");
        JsonNode session = login("restart@example.test");
        application.close();
        start();
        assertEquals(200, app("GET", "/api/users/me", null, session.path("accessToken").asText()).statusCode());
    }

    private static HttpRequest.Builder request(String method, String path, Object body) {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15));
        if (body == null) return builder.method(method, HttpRequest.BodyPublishers.noBody());
        return builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    }

    private static HttpResponse<String> app(String method, String path, Object body, String access) throws Exception {
        var builder = request(method, path, body).header("X-UNO-Client", "APP");
        if (access != null) builder.header("Authorization", "Bearer " + access);
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> internalSessions(UUID... ids) throws Exception {
        String basic = Base64.getEncoder().encodeToString(("game-service:" + SERVICE_KEY).getBytes(StandardCharsets.UTF_8));
        return CLIENT.send(request("POST", "/internal/auth/sessions/active", Map.of("sessionIds", ids))
                .header("Authorization", "Basic " + basic).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> register(String email, String password) throws Exception {
        return app("POST", "/api/auth/register", Map.of("email", email, "password", password, "nickname", "玩家"), null);
    }

    private void createVerified(String email) throws Exception {
        assertEquals(202, register(email, PASSWORD).statusCode());
        assertEquals(204, app("POST", "/api/auth/verify-email", Map.of("token", latestToken(email)), null).statusCode());
    }

    private String latestToken(String email) {
        var row = jdbc.queryForMap("SELECT id, recipient, encrypted_body FROM mail_outbox WHERE recipient = ? ORDER BY created_at DESC LIMIT 1", email);
        String body = application.getBean(MailCipher.class).decrypt((UUID) row.get("id"), (String) row.get("recipient"), (String) row.get("encrypted_body"));
        var matcher = java.util.regex.Pattern.compile("Token: ([A-Za-z0-9_-]{43})").matcher(body);
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    private JsonNode login(String email) throws Exception {
        var response = app("POST", "/api/auth/login", credentials(email, PASSWORD), null);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        return json(response);
    }

    private static Map<String, String> credentials(String email, String password) { return Map.of("email", email, "password", password); }
    private static JsonNode json(HttpResponse<String> response) { return JSON.readTree(response.body()); }

    private JsonNode mailpit(String path) throws Exception {
        return json(CLIENT.send(HttpRequest.newBuilder(URI.create("http://" + smtp.getHost() + ":" + smtp.getMappedPort(8025) + path)).GET().build(), HttpResponse.BodyHandlers.ofString()));
    }
}
