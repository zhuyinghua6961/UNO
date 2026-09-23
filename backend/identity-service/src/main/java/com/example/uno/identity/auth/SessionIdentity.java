package com.example.uno.identity.auth;

import java.time.Instant;
import java.util.UUID;

public record SessionIdentity(UUID userId, UUID sessionId, String email, String nickname,
        String clientType, Instant expiresAt) { }
