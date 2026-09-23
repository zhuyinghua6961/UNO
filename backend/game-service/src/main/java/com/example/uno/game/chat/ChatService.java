package com.example.uno.game.chat;

import com.example.uno.core.ChatMessage;
import com.example.uno.game.auth.GameIdentity;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Server owned identity, order and retention for room text. Team scope is added with 2v2. */
@Service
public class ChatService {
    private static final Duration RETENTION = Duration.ofDays(30);
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ChatService(JdbcTemplate jdbc, Clock roomClock) {
        this.jdbc = jdbc;
        this.clock = roomClock;
    }

    @Transactional
    public ChatItem send(UUID roomId, GameIdentity identity, UUID clientMessageId, String content) {
        if (clientMessageId == null) throw ChatFailure.invalid();
        try { new ChatMessage(content); }
        catch (IllegalArgumentException failure) { throw ChatFailure.invalid(); }
        Instant now = clock.instant();
        Member member = member(roomId, identity, true, now);
        ChatItem existing = one(jdbc.query("SELECT id, room_id, channel, sequence, sender_user_id, sender_nickname, "
                        + "client_message_id, content, created_at FROM game.chat_messages "
                        + "WHERE room_id = ? AND sender_user_id = ? AND client_message_id = ?",
                (rs, row) -> item(rs), roomId, identity.userId(), clientMessageId));
        if (existing != null) {
            if (!existing.content().equals(content)) throw ChatFailure.conflict();
            return existing;
        }
        Integer sentInSecond = jdbc.queryForObject("SELECT count(*) FROM game.chat_messages "
                        + "WHERE sender_user_id = ? AND created_at > ?", Integer.class,
                identity.userId(), Timestamp.from(now.minusSeconds(1)));
        if (sentInSecond != null && sentInSecond >= 2) throw ChatFailure.rateLimited();
        Long sequence = jdbc.queryForObject("INSERT INTO game.chat_channel_sequences(room_id, channel, last_sequence) "
                        + "VALUES (?, 'ROOM', 1) ON CONFLICT (room_id, channel) DO UPDATE SET "
                        + "last_sequence = game.chat_channel_sequences.last_sequence + 1 RETURNING last_sequence",
                Long.class, roomId);
        if (sequence == null) throw new IllegalStateException("Chat sequence allocation failed");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO game.chat_messages(id, room_id, channel, sequence, sender_user_id, "
                        + "sender_nickname, client_message_id, content, created_at, expires_at) "
                        + "VALUES (?, ?, 'ROOM', ?, ?, ?, ?, ?, ?, ?)",
                id, roomId, sequence, identity.userId(), member.nickname(), clientMessageId,
                content, Timestamp.from(now), Timestamp.from(now.plus(RETENTION)));
        return new ChatItem(id, roomId, "ROOM", sequence, identity.userId(), member.nickname(),
                clientMessageId, content, now);
    }

    @Transactional(readOnly = true)
    public ChatPage history(UUID roomId, GameIdentity identity, long after, int limit) {
        return history(roomId, identity, after, limit, false);
    }

    @Transactional(readOnly = true)
    public ChatPage history(UUID roomId, GameIdentity identity, long after, int limit, boolean latest) {
        if (after < 0 || limit < 1 || limit > 100) throw ChatFailure.invalid();
        Instant now = clock.instant();
        Member member = member(roomId, identity, false, now);
        if (latest && after != 0) throw ChatFailure.invalid();
        List<ChatItem> rows = jdbc.query("SELECT id, room_id, channel, sequence, sender_user_id, sender_nickname, "
                        + "client_message_id, content, created_at FROM game.chat_messages "
                        + "WHERE room_id = ? AND channel = 'ROOM' AND sequence > ? AND created_at >= ? "
                        + "AND expires_at > ? ORDER BY sequence " + (latest ? "DESC" : "ASC") + " LIMIT ?",
                (rs, row) -> item(rs), roomId, after, Timestamp.from(member.joinedAt()),
                Timestamp.from(now), limit + 1);
        if (latest) {
            List<ChatItem> recent = rows.size() > limit ? rows.subList(0, limit) : rows;
            List<ChatItem> ordered = new java.util.ArrayList<>(recent);
            java.util.Collections.reverse(ordered);
            return new ChatPage(ordered, ordered.isEmpty() ? 0 : ordered.get(ordered.size() - 1).sequence(), false);
        }
        boolean hasMore = rows.size() > limit;
        List<ChatItem> items = hasMore ? rows.subList(0, limit) : rows;
        return new ChatPage(items, items.isEmpty() ? after : items.get(items.size() - 1).sequence(), hasMore);
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void deleteExpired() {
        jdbc.update("DELETE FROM game.chat_messages WHERE expires_at <= ?", Timestamp.from(clock.instant()));
        jdbc.update("DELETE FROM game.chat_channel_sequences seq "
                + "WHERE NOT EXISTS (SELECT 1 FROM game.rooms r WHERE r.id = seq.room_id) "
                + "AND NOT EXISTS (SELECT 1 FROM game.chat_messages m WHERE m.room_id = seq.room_id)");
    }

    private Member member(UUID roomId, GameIdentity identity, boolean lock, Instant now) {
        String sql = "SELECT rm.nickname, rm.joined_at, r.state, r.expires_at FROM game.rooms r "
                + "JOIN game.room_members rm ON rm.room_id = r.id "
                + "WHERE r.id = ? AND rm.user_id = ?" + (lock ? " FOR UPDATE OF r" : "");
        Member result = one(jdbc.query(sql, (rs, row) -> new Member(rs.getString("nickname"),
                        rs.getTimestamp("joined_at").toInstant(), rs.getString("state"),
                        rs.getTimestamp("expires_at").toInstant()), roomId, identity.userId()));
        if (result == null || !("WAITING".equals(result.state()) || "PLAYING".equals(result.state()))
                || !result.expiresAt().isAfter(now)) throw ChatFailure.notFound();
        return result;
    }

    private static ChatItem item(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ChatItem(rs.getObject("id", UUID.class), rs.getObject("room_id", UUID.class),
                rs.getString("channel"), rs.getLong("sequence"), rs.getObject("sender_user_id", UUID.class),
                rs.getString("sender_nickname"), rs.getObject("client_message_id", UUID.class),
                rs.getString("content"), rs.getTimestamp("created_at").toInstant());
    }

    private static <T> T one(List<T> rows) { return rows.isEmpty() ? null : rows.get(0); }

    public record ChatItem(UUID id, UUID roomId, String channel, long sequence, UUID senderUserId,
            String senderNickname, UUID clientMessageId, String content, Instant createdAt) { }
    public record ChatPage(List<ChatItem> items, long nextSequence, boolean hasMore) {
        public ChatPage { items = List.copyOf(items); }
    }
    private record Member(String nickname, Instant joinedAt, String state, Instant expiresAt) { }
}
