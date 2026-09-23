package com.example.uno.game.rooms;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/** A per-process backstop until trusted edge/IP limits are deployed. */
public final class RoomJoinLimiter {
    private static final Duration WINDOW = Duration.ofMinutes(10);
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RoomJoinLimiter(Clock clock) { this.clock = clock; }

    public void acquire(String userId) {
        take("user:" + userId, 12);
    }

    private void take(String key, int limit) {
        Instant now = clock.instant();
        Window window = windows.compute(key, (ignored, previous) ->
                previous == null || !previous.until.isAfter(now)
                        ? new Window(now.plus(WINDOW), 1)
                        : new Window(previous.until, previous.count + 1));
        if (windows.size() > 10000) windows.entrySet().removeIf(entry -> !entry.getValue().until.isAfter(now));
        if (window.count > limit) throw RoomFailure.rateLimited();
    }

    private record Window(Instant until, int count) { }
}
