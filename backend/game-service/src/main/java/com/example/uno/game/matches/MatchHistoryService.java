package com.example.uno.game.matches;

import com.example.uno.core.rules.UnoSnapshot;
import com.example.uno.game.auth.GameIdentity;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Terminal results use persisted match and player snapshots, never mutable room seats. */
@Service
public class MatchHistoryService {
    private final JdbcTemplate jdbc;
    private final JsonMapper json = new JsonMapper();

    public MatchHistoryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public Page history(GameIdentity identity, String cursorText, int limit) {
        if (limit < 1 || limit > 50) throw MatchFailure.invalid();
        Cursor cursor = decode(cursorText);
        String sql = "SELECT m.id, m.mode, m.ended_at, m.snapshot::text, m.state, self.team_snapshot FROM game.matches m "
                + "JOIN game.match_players self ON self.match_id = m.id "
                + "WHERE self.user_id = ? AND m.mode IN ('CLASSIC', 'TEAM_2V2') "
                + "AND m.state IN ('ENDED', 'INTERRUPTED') AND m.ended_at IS NOT NULL";
        List<Object> arguments = new ArrayList<>();
        arguments.add(identity.userId());
        if (cursor != null) {
            sql += " AND (m.ended_at, m.id) < (?, ?)";
            arguments.add(Timestamp.from(cursor.endedAt()));
            arguments.add(cursor.matchId());
        }
        sql += " ORDER BY m.ended_at DESC, m.id DESC LIMIT ?";
        arguments.add(limit + 1);
        List<Row> rows = jdbc.query(sql, (rs, row) -> new Row(rs.getObject(1, UUID.class),
                rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getString(4), rs.getString(5), rs.getString(6)),
                arguments.toArray());
        boolean hasMore = rows.size() > limit;
        List<Summary> items = new ArrayList<>();
        for (Row row : rows.subList(0, Math.min(rows.size(), limit))) {
            UnoSnapshot snapshot = json.readValue(row.snapshot(), UnoSnapshot.class);
            List<Player> players = jdbc.query("SELECT user_id, seat, nickname_snapshot FROM game.match_players "
                            + "WHERE match_id = ? ORDER BY seat",
                    (rs, index) -> new Player(rs.getObject("user_id", UUID.class), rs.getInt("seat"),
                            rs.getString("nickname_snapshot"), snapshot.scores().get(rs.getInt("seat"))), row.id());
            Integer winnerSeat = snapshot.roundWinnerSeat();
            UUID winner = "INTERRUPTED".equals(row.status()) ? null : snapshot.players().get(winnerSeat);
            boolean won = winner != null && ("TEAM_2V2".equals(row.mode())
                    ? (winnerSeat % 2 == 0 ? "A" : "B").equals(row.teamSnapshot())
                    : identity.userId().equals(winner));
            items.add(new Summary(row.id(), row.mode(), row.endedAt(), snapshot.roundNumber(),
                    winner, winner == null ? "INTERRUPTED" : won ? "WIN" : "LOSS", List.copyOf(players)));
        }
        String next = hasMore ? encode(rows.get(limit - 1)) : null;
        return new Page(List.copyOf(items), next);
    }

    private static Cursor decode(String text) {
        if (text == null) return null;
        if (text.length() > 160) throw MatchFailure.invalid();
        try {
            String value = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", -1);
            if (parts.length != 2) throw MatchFailure.invalid();
            return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException failure) {
            throw MatchFailure.invalid();
        }
    }

    private static String encode(Row row) {
        String value = row.endedAt() + "|" + row.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public record Page(List<Summary> items, String nextCursor) { }
    public record Summary(UUID matchId, String mode, Instant endedAt, int rounds,
            UUID winnerUserId, String result, List<Player> players) { }
    public record Player(UUID userId, int seat, String nickname, int score) { }
    private record Cursor(Instant endedAt, UUID matchId) { }
    private record Row(UUID id, String mode, Instant endedAt, String snapshot, String status,
            String teamSnapshot) { }
}
