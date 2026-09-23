package com.example.uno.game.matches;

import com.example.uno.game.realtime.GameWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls persisted deadlines; MatchService rechecks each one under its match row lock. */
@Component
@ConditionalOnProperty(name = "uno.matches.deadline-worker-enabled", havingValue = "true", matchIfMissing = true)
public class MatchDeadlineWorker {
    private static final Logger LOG = LoggerFactory.getLogger(MatchDeadlineWorker.class);
    private final MatchService matches;
    private final GameWebSocketHandler socket;

    public MatchDeadlineWorker(MatchService matches, GameWebSocketHandler socket) {
        this.matches = matches;
        this.socket = socket;
    }

    @Scheduled(fixedDelayString = "${uno.matches.deadline-poll-ms:1000}")
    public void resolveDueMatches() {
        for (var matchId : matches.dueMatches()) {
            try {
                if (matches.resolveTimeout(matchId) != null) socket.publish(matchId);
            } catch (RuntimeException failure) {
                LOG.error("Could not resolve expired match {}", matchId, failure);
            }
        }
    }
}
