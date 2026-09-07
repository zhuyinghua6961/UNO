package com.example.uno.identity.persistence;

import java.time.Instant;
import java.util.UUID;

public record Account(UUID id, String email, String nickname, String status, Instant createdAt) {
}
