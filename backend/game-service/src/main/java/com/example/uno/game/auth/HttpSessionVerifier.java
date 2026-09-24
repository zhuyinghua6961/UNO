package com.example.uno.game.auth;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class HttpSessionVerifier {
    private final HttpClient client;
    private final JsonMapper json;
    private final GameAuthSettings settings;
    private final Clock clock;
    private final String authorization;

    public HttpSessionVerifier(HttpClient client, JsonMapper json, GameAuthSettings settings, Clock clock) {
        this.client = client;
        this.json = json;
        this.settings = settings;
        this.clock = clock;
        authorization = "Basic " + Base64.getEncoder().encodeToString(("game-service:" + settings.serviceKey()).getBytes(StandardCharsets.UTF_8));
    }

    public Optional<GameIdentity> verify(String token, String clientType) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}") || !("WEB".equals(clientType) || "APP".equals(clientType))) return Optional.empty();
        if (!settings.enabled()) throw GameAuthFailure.unavailable();
        HttpRequest request = HttpRequest.newBuilder(settings.identityBaseUrl().resolve("/internal/auth/introspect"))
                .timeout(Duration.ofMillis(settings.timeoutMillis()))
                .header("Authorization", authorization).header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("token", token, "clientType", clientType))))
                .build();
        var pending = client.sendAsync(request, info -> new LimitedResponseBody(4096));
        try {
            HttpResponse<byte[]> response = pending.get(settings.timeoutMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200 || !response.headers().firstValue("Content-Type").orElse("")
                    .split(";", 2)[0].strip().equalsIgnoreCase("application/json")) throw GameAuthFailure.unavailable();
            JsonNode body = json.readTree(response.body());
            if (body == null || !body.isObject() || !body.path("active").isBoolean()) throw GameAuthFailure.unavailable();
            if (!body.path("active").asBoolean()) return Optional.empty();
            if (!"game-service".equals(text(body, "audience")) || !body.path("protocolVersion").isIntegralNumber()
                    || body.path("protocolVersion").asInt() != 1 || !clientType.equals(text(body, "clientType"))) throw GameAuthFailure.unavailable();
            Instant expiry = Instant.parse(text(body, "expiresAt"));
            UUID userId = UUID.fromString(text(body, "userId"));
            UUID sessionId = UUID.fromString(text(body, "sessionId"));
            String nickname = text(body, "nickname");
            if (nickname.isBlank() || nickname.length() > 80) throw GameAuthFailure.unavailable();
            if (!expiry.isAfter(clock.instant())) return Optional.empty();
            return Optional.of(new GameIdentity(userId, sessionId, nickname, clientType, expiry));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw GameAuthFailure.unavailable();
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            throw GameAuthFailure.unavailable();
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    public Set<UUID> activeSessionIds(Set<UUID> sessionIds) {
        if (sessionIds.isEmpty()) return Set.of();
        if (sessionIds.size() > 64 || !settings.enabled()) throw GameAuthFailure.unavailable();
        HttpRequest request = HttpRequest.newBuilder(settings.identityBaseUrl().resolve("/internal/auth/sessions/active"))
                .timeout(Duration.ofMillis(settings.timeoutMillis()))
                .header("Authorization", authorization).header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("sessionIds", sessionIds))))
                .build();
        var pending = client.sendAsync(request, info -> new LimitedResponseBody(8192));
        try {
            HttpResponse<byte[]> response = pending.get(settings.timeoutMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200 || !response.headers().firstValue("Content-Type").orElse("")
                    .split(";", 2)[0].strip().equalsIgnoreCase("application/json")) throw GameAuthFailure.unavailable();
            JsonNode body = json.readTree(response.body());
            if (body == null || !body.isObject() || !body.path("activeSessionIds").isArray()) throw GameAuthFailure.unavailable();
            Set<UUID> active = new HashSet<>();
            for (JsonNode item : body.path("activeSessionIds")) {
                if (!item.isTextual()) throw GameAuthFailure.unavailable();
                UUID id = UUID.fromString(item.asText());
                if (!sessionIds.contains(id)) throw GameAuthFailure.unavailable();
                active.add(id);
            }
            return Set.copyOf(active);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw GameAuthFailure.unavailable();
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            throw GameAuthFailure.unavailable();
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    private String text(JsonNode node, String name) {
        if (!node.path(name).isTextual()) throw GameAuthFailure.unavailable();
        return node.path(name).asText();
    }
}
