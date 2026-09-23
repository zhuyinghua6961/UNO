package com.example.uno.game.auth;

import java.time.Instant;
import java.util.UUID;

public record GameIdentity(UUID userId, UUID sessionId, String nickname, String clientType, Instant expiresAt) { }
