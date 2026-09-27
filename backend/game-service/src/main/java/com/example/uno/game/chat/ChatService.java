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
import org.springframework.transaction.annotation.Isolation;

/** Server owned identity, order, audience and retention for room and team text. */
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
        return send(roomId, identity, clientMessageId, content, "ROOM");
    }

    @Transactional
    public ChatItem send(UUID roomId, GameIdentity identity, UUID clientMessageId, String content, String scope) {
        if (clientMessageId == null) throw ChatFailure.invalid();
        try { new ChatMessage(content); }
        catch (IllegalArgumentException failure) { throw ChatFailure.invalid(); }
        Instant now = clock.instant();
        Member member = member(roomId, identity, true, now);
        String channel = channel(member, scope);
        ChatItem existing = one(jdbc.query("SELECT id, room_id, channel, sequence, sender_user_id, sender_nickname, "
                        + "client_message_id, content, created_at, redacted_at FROM game.chat_messages "
                        + "WHERE room_id = ? AND sender_user_id = ? AND client_message_id = ?",
                (rs, row) -> item(rs), roomId, identity.userId(), clientMessageId));
        if (existing != null) {
            if ((!existing.redacted() && !existing.content().equals(content))
                    || !existing.channel().equals(channel)) throw ChatFailure.conflict();
            return existing;
        }
        Boolean muted = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM game.chat_mutes "
                + "WHERE user_id = ? AND muted_until > ?)", Boolean.class, identity.userId(), Timestamp.from(now));
        if (Boolean.TRUE.equals(muted)) throw ChatFailure.muted();
        Integer sentInSecond = jdbc.queryForObject("SELECT count(*) FROM game.chat_messages "
                        + "WHERE sender_user_id = ? AND created_at > ?", Integer.class,
                identity.userId(), Timestamp.from(now.minusSeconds(1)));
        if (sentInSecond != null && sentInSecond >= 2) throw ChatFailure.rateLimited();
        Long sequence = jdbc.queryForObject("INSERT INTO game.chat_channel_sequences(room_id, channel, last_sequence) "
                        + "VALUES (?, ?, 1) ON CONFLICT (room_id, channel) DO UPDATE SET "
                        + "last_sequence = game.chat_channel_sequences.last_sequence + 1 RETURNING last_sequence",
                Long.class, roomId, channel);
        if (sequence == null) throw new IllegalStateException("Chat sequence allocation failed");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO game.chat_messages(id, room_id, channel, sequence, sender_user_id, "
                        + "sender_nickname, client_message_id, content, created_at, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, roomId, channel, sequence, identity.userId(), member.nickname(), clientMessageId,
                content, Timestamp.from(now), Timestamp.from(now.plus(RETENTION)));
        return new ChatItem(id, roomId, channel, sequence, identity.userId(), member.nickname(),
                clientMessageId, content, now, false);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ChatPage history(UUID roomId, GameIdentity identity, long after, int limit) {
        return history(roomId, identity, after, limit, false);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ChatPage history(UUID roomId, GameIdentity identity, long after, int limit, boolean latest) {
        return history(roomId, identity, after, limit, latest, "ROOM");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ChatPage history(UUID roomId, GameIdentity identity, long after, int limit,
            boolean latest, String scope) {
        return subscriptionPage(roomId, identity, null, after, limit, latest, scope).page();
    }

    /** Fetch a subscription page under one membership snapshot, resetting the cursor after a team change. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SubscriptionPage subscriptionPage(UUID roomId, GameIdentity identity, String expectedChannel,
            long after, int limit, boolean latest, String scope) {
        if (after < 0 || limit < 1 || limit > 100) throw ChatFailure.invalid();
        Instant now = clock.instant();
        Member member = member(roomId, identity, false, now);
        String channel = channel(member, scope);
        if (latest && after != 0) throw ChatFailure.invalid();
        if (expectedChannel != null && !expectedChannel.equals(channel)) after = 0;
        long floor = "ROOM".equals(channel) ? 0 : member.teamJoinSequence();
        List<ChatItem> rows = jdbc.query("SELECT id, room_id, channel, sequence, sender_user_id, sender_nickname, "
                        + "client_message_id, content, created_at, redacted_at FROM game.chat_messages "
                        + "WHERE room_id = ? AND channel = ? AND sequence > ? AND created_at >= ? "
                        + "AND expires_at > ? ORDER BY sequence " + (latest ? "DESC" : "ASC") + " LIMIT ?",
                (rs, row) -> item(rs), roomId, channel, Math.max(after, floor), Timestamp.from(member.joinedAt()),
                Timestamp.from(now), limit + 1);
        if (latest) {
            List<ChatItem> recent = rows.size() > limit ? rows.subList(0, limit) : rows;
            List<ChatItem> ordered = new java.util.ArrayList<>(recent);
            java.util.Collections.reverse(ordered);
            return new SubscriptionPage(channel, new ChatPage(ordered,
                    ordered.isEmpty() ? floor : ordered.get(ordered.size() - 1).sequence(), false));
        }
        boolean hasMore = rows.size() > limit;
        List<ChatItem> items = hasMore ? rows.subList(0, limit) : rows;
        return new SubscriptionPage(channel, new ChatPage(items,
                items.isEmpty() ? Math.max(after, floor) : items.get(items.size() - 1).sequence(), hasMore));
    }

    /** Recheck the current room/team entitlement immediately before a live delivery. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public boolean visibleTo(ChatItem item, GameIdentity identity) {
        String scope = "ROOM".equals(item.channel()) ? "ROOM" : "TEAM";
        ChatPage page = history(item.roomId(), identity, item.sequence() - 1, 1, false, scope);
        return page.items().stream().anyMatch(candidate -> candidate.id().equals(item.id()));
    }

    /** A report is accepted only for a message the current member may read. */
    @Transactional
    public ReportReceipt report(UUID roomId, GameIdentity identity, UUID messageId, String reason) {
        if (reason == null || !List.of("SPAM", "ABUSE", "OTHER").contains(reason)) throw ChatFailure.invalid();
        ChatItem target = one(jdbc.query("SELECT id, room_id, channel, sequence, sender_user_id, "
                        + "sender_nickname, client_message_id, content, created_at, redacted_at "
                        + "FROM game.chat_messages WHERE room_id = ? AND id = ? AND expires_at > ?",
                (rs, row) -> item(rs), roomId, messageId, Timestamp.from(clock.instant())));
        if (target == null || !visibleTo(target, identity)) throw ChatFailure.reportNotFound();
        ReportReceipt existing = one(jdbc.query("SELECT id, status FROM game.chat_reports "
                        + "WHERE message_id = ? AND reporter_user_id = ?",
                (rs, row) -> new ReportReceipt(rs.getObject("id", UUID.class), rs.getString("status")),
                messageId, identity.userId()));
        if (existing != null) return existing;
        Instant now = clock.instant();
        Integer recent = jdbc.queryForObject("SELECT count(*) FROM game.chat_reports "
                + "WHERE reporter_user_id = ? AND created_at > ?", Integer.class,
                identity.userId(), Timestamp.from(now.minus(Duration.ofDays(1))));
        if (recent != null && recent >= 20) throw ChatFailure.rateLimited();
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO game.chat_reports(id, room_id, message_id, reporter_user_id, "
                        + "reported_user_id, reason, content_snapshot, created_at, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (message_id, reporter_user_id) DO NOTHING",
                id, roomId, messageId, identity.userId(), target.senderUserId(), reason, target.content(),
                Timestamp.from(now), Timestamp.from(now.plus(RETENTION)));
        ReportReceipt saved = one(jdbc.query("SELECT id, status FROM game.chat_reports "
                        + "WHERE message_id = ? AND reporter_user_id = ?",
                (rs, row) -> new ReportReceipt(rs.getObject("id", UUID.class), rs.getString("status")),
                messageId, identity.userId()));
        return saved;
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void deleteExpired() {
        jdbc.update("DELETE FROM game.chat_reports WHERE expires_at <= ?", Timestamp.from(clock.instant()));
        jdbc.update("DELETE FROM game.chat_mutes WHERE muted_until <= ?",
                Timestamp.from(clock.instant().minus(RETENTION)));
        jdbc.update("DELETE FROM game.chat_messages WHERE expires_at <= ?", Timestamp.from(clock.instant()));
        jdbc.update("DELETE FROM game.chat_channel_sequences seq "
                + "WHERE NOT EXISTS (SELECT 1 FROM game.rooms r WHERE r.id = seq.room_id) "
                + "AND NOT EXISTS (SELECT 1 FROM game.chat_messages m WHERE m.room_id = seq.room_id)");
    }

    private Member member(UUID roomId, GameIdentity identity, boolean lock, Instant now) {
        String sql = "SELECT rm.nickname, rm.joined_at, rm.seat, rm.team_join_sequence, "
                + "r.mode, r.state, r.expires_at FROM game.rooms r "
                + "JOIN game.room_members rm ON rm.room_id = r.id "
                + "WHERE r.id = ? AND rm.user_id = ?" + (lock ? " FOR UPDATE OF r" : "");
        Member result = one(jdbc.query(sql, (rs, row) -> new Member(rs.getString("nickname"),
                        rs.getTimestamp("joined_at").toInstant(), rs.getInt("seat"),
                        rs.getLong("team_join_sequence"), rs.getString("mode"), rs.getString("state"),
                        rs.getTimestamp("expires_at").toInstant()), roomId, identity.userId()));
        if (result == null || !("WAITING".equals(result.state()) || "PLAYING".equals(result.state()))
                || !result.expiresAt().isAfter(now)) throw ChatFailure.notFound();
        return result;
    }

    private String channel(Member member, String scope) {
        if ("ROOM".equals(scope)) return "ROOM";
        if ("TEAM".equals(scope) && "TEAM_2V2".equals(member.mode()))
            return member.seat() % 2 == 0 ? "TEAM_A" : "TEAM_B";
        throw ChatFailure.invalid();
    }

    private static ChatItem item(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ChatItem(rs.getObject("id", UUID.class), rs.getObject("room_id", UUID.class),
                rs.getString("channel"), rs.getLong("sequence"), rs.getObject("sender_user_id", UUID.class),
                rs.getString("sender_nickname"), rs.getObject("client_message_id", UUID.class),
                rs.getString("content"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("redacted_at") != null);
    }

    private static <T> T one(List<T> rows) { return rows.isEmpty() ? null : rows.get(0); }

    public record ChatItem(UUID id, UUID roomId, String channel, long sequence, UUID senderUserId,
            String senderNickname, UUID clientMessageId, String content, Instant createdAt, boolean redacted) { }
    public record ChatPage(List<ChatItem> items, long nextSequence, boolean hasMore) {
        public ChatPage { items = List.copyOf(items); }
    }
    public record SubscriptionPage(String channel, ChatPage page) { }
    public record ReportReceipt(UUID id, String status) { }
    private record Member(String nickname, Instant joinedAt, int seat, long teamJoinSequence,
            String mode, String state, Instant expiresAt) { }
}
