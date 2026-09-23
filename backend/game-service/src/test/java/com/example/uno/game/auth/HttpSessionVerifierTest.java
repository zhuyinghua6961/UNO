package com.example.uno.game.auth;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class HttpSessionVerifierTest {
    private static final String TOKEN = "a".repeat(43);
    private static final String KEY = "b".repeat(64);
    private final JsonMapper json = new JsonMapper();
    private HttpServer server;
    private URI base;
    private String body;
    private int status;
    private long delay;
    private long bodyDelay;
    private final AtomicInteger calls = new AtomicInteger();
    private String capturedAuthorization;
    private String capturedBody;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        body = active();
        status = 200;
        server.createContext("/internal/auth/introspect", exchange -> {
            calls.incrementAndGet();
            capturedAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            capturedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try { Thread.sleep(delay); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (status == 302) exchange.getResponseHeaders().set("Location", base + "/must-not-follow");
            try {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                if (bodyDelay > 0) {
                    exchange.getResponseBody().write(bytes, 0, 1);
                    exchange.getResponseBody().flush();
                    try { Thread.sleep(bodyDelay); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                    exchange.getResponseBody().write(bytes, 1, bytes.length - 1);
                    return;
                }
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.createContext("/must-not-follow", exchange -> { calls.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close(); });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void validResponseIsMinimalAndCheckedEveryTime() {
        var verifier = verifier();
        assertTrue(verifier.verify(TOKEN, "APP").isPresent());
        assertEquals("Basic " + Base64.getEncoder().encodeToString(("game-service:" + KEY).getBytes(StandardCharsets.UTF_8)), capturedAuthorization);
        assertEquals(Map.of("token", TOKEN, "clientType", "APP"), json.readValue(capturedBody, Map.class));
        body = "{\"active\":false}";
        assertTrue(verifier.verify(TOKEN, "APP").isEmpty());
        assertEquals(2, calls.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 400, 401, 403, 429, 500, 503})
    void dependencyFailuresNeverBecomeSuccessfulAuthentication(int failureStatus) {
        status = failureStatus;
        assertEquals(503, assertThrows(GameAuthFailure.class, () -> verifier().verify(TOKEN, "APP")).status());
        assertEquals(1, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "{}", "[]", "null", "{\"active\":\"true\"}", "{\"active\":true}",
            "{\"active\":true,\"audience\":\"other\"}"})
    void malformedResponsesFailClosed(String malformed) {
        body = malformed;
        assertEquals(503, assertThrows(GameAuthFailure.class, () -> verifier().verify(TOKEN, "APP")).status());
    }

    @Test
    void wrongClientAndExpiredResponseCannotAuthorize() {
        body = active().replace("\"APP\"", "\"WEB\"");
        assertEquals(503, assertThrows(GameAuthFailure.class, () -> verifier().verify(TOKEN, "APP")).status());
        body = json.writeValueAsString(Map.of("active", true, "audience", "game-service", "protocolVersion", 1,
                "clientType", "APP", "userId", UUID.randomUUID(), "sessionId", UUID.randomUUID(), "nickname", "Tester", "expiresAt", Instant.now().minusSeconds(10).toString()));
        assertTrue(verifier().verify(TOKEN, "APP").isEmpty());
    }

    @Test
    void oversizedResponseAndTimeoutFailClosed() {
        body = " ".repeat(5000) + active();
        assertEquals(503, assertThrows(GameAuthFailure.class, () -> verifier().verify(TOKEN, "APP")).status());
        body = active();
        delay = 2000;
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertEquals(503,
                assertThrows(GameAuthFailure.class, () -> verifier().verify(TOKEN, "APP")).status()));
    }

    @Test
    void incompleteResponseBodyCannotHoldAuthenticationOpen() {
        bodyDelay = 2000;
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertEquals(503,
                assertThrows(GameAuthFailure.class, () -> verifier().verify(TOKEN, "APP")).status()));
    }

    @Test
    void invalidCredentialsAreRejectedWithoutNetworkRequest() {
        assertTrue(verifier().verify("invalid", "APP").isEmpty());
        assertTrue(verifier().verify(TOKEN, "ADMIN").isEmpty());
        assertEquals(0, calls.get());
    }

    @Test
    void invalidServiceConfigurationAndSecretPrintingArePrevented() {
        assertThrows(IllegalArgumentException.class, () -> settings(URI.create("http://example.test"), KEY));
        assertThrows(IllegalArgumentException.class, () -> settings(URI.create("https://user:pass@example.test"), KEY));
        assertThrows(IllegalArgumentException.class, () -> settings(URI.create("https://example.test/redirect"), KEY));
        assertThrows(IllegalArgumentException.class, () -> settings(base, "short"));
        assertFalse(settings(base, KEY).toString().contains(KEY));
    }

    private HttpSessionVerifier verifier() {
        return new HttpSessionVerifier(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofMillis(300)).build(),
                json, settings(base, KEY), Clock.systemUTC());
    }

    private GameAuthSettings settings(URI uri, String key) {
        return new GameAuthSettings(true, false, List.of("http://localhost:5179"), uri, key, true, 500);
    }

    private String active() {
        return json.writeValueAsString(Map.of("active", true, "audience", "game-service", "protocolVersion", 1,
                "clientType", "APP", "userId", UUID.randomUUID(), "sessionId", UUID.randomUUID(), "nickname", "Tester", "expiresAt", Instant.now().plusSeconds(120).toString()));
    }
}
