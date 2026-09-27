package com.example.uno.game.realtime;

import com.example.uno.game.auth.GameIdentity;
import com.example.uno.game.matches.MatchCommandInput;
import com.example.uno.game.matches.MatchService;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One database-owned command socket per match player, shared by every Game instance. */
@Service
public class MatchSocketOwnership {
    private final JdbcTemplate jdbc;
    private final MatchService matches;

    public MatchSocketOwnership(JdbcTemplate jdbc, MatchService matches) {
        this.jdbc = jdbc;
        this.matches = matches;
    }

    @Transactional
    public void claim(UUID matchId, UUID userId, UUID ownerToken) {
        jdbc.update("INSERT INTO game.match_socket_ownership(match_id, user_id, owner_token) "
                        + "VALUES (?, ?, ?) ON CONFLICT (match_id, user_id) DO UPDATE "
                        + "SET owner_token = EXCLUDED.owner_token, claimed_at = now()",
                matchId, userId, ownerToken);
    }

    @Transactional(readOnly = true)
    public boolean isCurrent(UUID matchId, UUID userId, UUID ownerToken) {
        return ownerToken.equals(one(jdbc.query(
                "SELECT owner_token FROM game.match_socket_ownership WHERE match_id = ? AND user_id = ?",
                (rs, row) -> rs.getObject(1, UUID.class), matchId, userId)));
    }

    /** The ownership row lock serializes a command against a takeover on another instance. */
    @Transactional
    public MatchService.CommandResult command(UUID matchId, GameIdentity identity, UUID ownerToken,
            MatchCommandInput input) {
        UUID current = one(jdbc.query("SELECT owner_token FROM game.match_socket_ownership "
                        + "WHERE match_id = ? AND user_id = ? FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class), matchId, identity.userId()));
        if (!ownerToken.equals(current)) throw new TakenOver();
        return matches.command(matchId, identity, input);
    }

    private static <T> T one(List<T> rows) { return rows.isEmpty() ? null : rows.get(0); }

    public static final class TakenOver extends RuntimeException { }
}
