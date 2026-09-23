package com.example.uno.identity.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AuthRateLimiter {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AuthRateLimiter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void acquire(String scope, String subject, int limit) {
        Instant now = clock.instant();
        Integer count = jdbc.queryForObject("""
                INSERT INTO auth_rate_limits (bucket_key, window_started_at, attempts) VALUES (?, ?, 1)
                ON CONFLICT (bucket_key) DO UPDATE SET
                    attempts = CASE WHEN auth_rate_limits.window_started_at <= ? THEN 1
                        ELSE LEAST(auth_rate_limits.attempts + 1, 1000000) END,
                    window_started_at = CASE WHEN auth_rate_limits.window_started_at <= ? THEN EXCLUDED.window_started_at
                        ELSE auth_rate_limits.window_started_at END
                RETURNING attempts
                """, Integer.class, Secrets.digest(scope + ":" + subject), Timestamp.from(now),
                Timestamp.from(now.minus(Duration.ofMinutes(15))), Timestamp.from(now.minus(Duration.ofMinutes(15))));
        if (count == null || count > limit) throw new AuthFailure(429, "RATE_LIMITED", "请求过于频繁，请稍后再试");
    }

    @Scheduled(initialDelay = 3600000, fixedDelay = 3600000)
    public void prune() {
        jdbc.update("DELETE FROM auth_rate_limits WHERE window_started_at < ?",
                Timestamp.from(clock.instant().minus(Duration.ofDays(1))));
    }
}
