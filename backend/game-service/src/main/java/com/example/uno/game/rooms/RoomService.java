package com.example.uno.game.rooms;

import com.example.uno.game.auth.GameIdentity;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomService {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Duration LIFETIME = Duration.ofHours(24);
    private static final String ROOM_COLUMNS = "id, code, mode, max_players, host_user_id, state, version, expires_at";
    private static final RowMapper<RoomRow> ROOM_MAPPER = (rs, row) -> new RoomRow(
            rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("mode"),
            rs.getInt("max_players"), rs.getObject("host_user_id", UUID.class),
            rs.getString("state"), rs.getLong("version"), rs.getTimestamp("expires_at").toInstant());

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final RoomJoinLimiter limiter;
    private final SecureRandom random = new SecureRandom();

    public RoomService(JdbcTemplate jdbc, Clock roomClock, RoomJoinLimiter limiter) {
        this.jdbc = jdbc;
        this.clock = roomClock;
        this.limiter = limiter;
    }

    @Transactional
    public RoomView create(GameIdentity identity, String mode, int maxPlayers) {
        validateSettings(mode, maxPlayers);
        deleteExpired();
        UUID existing = currentRoomId(identity.userId());
        if (existing != null) return view(requiredRoom(existing));
        UUID id = UUID.randomUUID();
        String code = newCode();
        jdbc.update("INSERT INTO game.rooms(id, code, mode, max_players, host_user_id, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, code, mode, maxPlayers, identity.userId(), Timestamp.from(clock.instant().plus(LIFETIME)));
        jdbc.update("INSERT INTO game.room_members(room_id, user_id, nickname, seat) VALUES (?, ?, ?, 0)",
                id, identity.userId(), identity.nickname());
        return view(requiredRoom(id));
    }

    @Transactional
    public RoomView join(GameIdentity identity, String rawCode) {
        limiter.acquire(identity.userId().toString());
        String code = rawCode == null ? "" : rawCode.strip().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-HJ-NP-Z2-9]{10}")) throw RoomFailure.invalid();
        deleteExpired();
        RoomRow room = roomByCodeForUpdate(code);
        if (room == null || !room.expiresAt().isAfter(clock.instant())) throw RoomFailure.notFound();
        if (!"WAITING".equals(room.state())) throw RoomFailure.conflict("对局已开始，无法加入等待房间");
        UUID existing = currentRoomId(identity.userId());
        if (room.id().equals(existing)) return view(room);
        if (existing != null) throw RoomFailure.conflict("你已在其他房间，请先离开");
        List<RoomView.Member> members = members(room);
        if (members.size() >= room.maxPlayers()) throw RoomFailure.full();
        int seat = firstFreeSeat(members, room.maxPlayers(), null);
        jdbc.update("INSERT INTO game.room_members(room_id, user_id, nickname, seat, team_join_sequence) "
                        + "VALUES (?, ?, ?, ?, ?)",
                room.id(), identity.userId(), identity.nickname(), seat,
                "TEAM_2V2".equals(room.mode()) ? teamSequence(room.id(), seat) : 0);
        changed(room.id());
        return view(requiredRoom(room.id()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RoomView current(GameIdentity identity) {
        UUID id = currentRoomId(identity.userId());
        if (id == null) return null;
        RoomRow room = room(id);
        return room == null || ("WAITING".equals(room.state()) && !room.expiresAt().isAfter(clock.instant()))
                ? null : view(room);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RoomView get(UUID id, GameIdentity identity) {
        if (!isMember(id, identity.userId())) throw RoomFailure.notFound();
        RoomRow room = room(id);
        if (room == null || ("WAITING".equals(room.state()) && !room.expiresAt().isAfter(clock.instant())))
            throw RoomFailure.notFound();
        return view(room);
    }

    @Transactional
    public void leave(UUID id, GameIdentity identity) {
        RoomRow room = lockedRoom(id);
        if (room == null || !isMember(id, identity.userId())) return;
        if (!"WAITING".equals(room.state())) throw RoomFailure.conflict("对局进行中，暂不能离开房间");
        jdbc.update("DELETE FROM game.room_members WHERE room_id = ? AND user_id = ?", id, identity.userId());
        List<RoomView.Member> remaining = members(room);
        if (remaining.isEmpty()) {
            jdbc.update("DELETE FROM game.rooms WHERE id = ?", id);
            return;
        }
        if (identity.userId().equals(room.hostUserId())) {
            UUID successor = jdbc.queryForObject(
                    "SELECT user_id FROM game.room_members WHERE room_id = ? ORDER BY joined_at, user_id LIMIT 1",
                    UUID.class, id);
            jdbc.update("UPDATE game.rooms SET host_user_id = ? WHERE id = ?", successor, id);
        }
        changed(id);
    }

    @Transactional
    public RoomView ready(UUID id, GameIdentity identity, boolean ready, long expectedVersion) {
        RoomRow room = memberRoomForUpdate(id, identity.userId());
        requireVersionAndWaiting(room, expectedVersion);
        int updated = jdbc.update("UPDATE game.room_members SET ready = ? WHERE room_id = ? AND user_id = ? AND ready <> ?",
                ready, id, identity.userId(), ready);
        if (updated != 0) incrementVersion(id);
        return view(requiredRoom(id));
    }

    @Transactional
    public RoomView selectTeam(UUID id, GameIdentity identity, String team, long expectedVersion) {
        RoomRow room = memberRoomForUpdate(id, identity.userId());
        requireVersionAndWaiting(room, expectedVersion);
        if (!"TEAM_2V2".equals(room.mode()) || !("A".equals(team) || "B".equals(team))) throw RoomFailure.invalid();
        List<RoomView.Member> members = members(room);
        RoomView.Member self = members.stream().filter(member -> member.userId().equals(identity.userId())).findFirst().orElseThrow(RoomFailure::notFound);
        if (team.equals(self.team())) return view(room);
        int seat = firstFreeSeat(members, 4, team);
        if (seat < 0) throw RoomFailure.full();
        jdbc.update("UPDATE game.room_members SET seat = ?, team_join_sequence = ? "
                        + "WHERE room_id = ? AND user_id = ?",
                seat, teamSequence(id, seat), id, identity.userId());
        changed(id);
        return view(requiredRoom(id));
    }

    @Transactional
    public RoomView changeMaxPlayers(UUID id, GameIdentity identity, int maxPlayers, long expectedVersion) {
        RoomRow room = memberRoomForUpdate(id, identity.userId());
        requireVersionAndWaiting(room, expectedVersion);
        if (!room.hostUserId().equals(identity.userId())) throw RoomFailure.forbidden();
        validateSettings(room.mode(), maxPlayers);
        if (members(room).size() > maxPlayers) throw RoomFailure.conflict("人数上限不能小于当前人数");
        if (room.maxPlayers() == maxPlayers) return view(room);
        jdbc.update("UPDATE game.rooms SET max_players = ? WHERE id = ?", maxPlayers, id);
        changed(id);
        return view(requiredRoom(id));
    }

    @Scheduled(fixedDelay = 600000)
    @Transactional
    public void expireRooms() { deleteExpired(); }

    private void deleteExpired() {
        jdbc.update("DELETE FROM game.rooms WHERE state = 'WAITING' AND expires_at <= ?",
                Timestamp.from(clock.instant()));
    }

    private void changed(UUID id) {
        jdbc.update("UPDATE game.room_members SET ready = FALSE WHERE room_id = ?", id);
        incrementVersion(id);
    }

    private long teamSequence(UUID roomId, int seat) {
        Long sequence = single(jdbc.query("SELECT last_sequence FROM game.chat_channel_sequences "
                        + "WHERE room_id = ? AND channel = ?",
                (rs, row) -> rs.getLong(1), roomId, seat % 2 == 0 ? "TEAM_A" : "TEAM_B"));
        return sequence == null ? 0 : sequence;
    }

    private void incrementVersion(UUID id) {
        jdbc.update("UPDATE game.rooms SET version = version + 1 WHERE id = ?", id);
    }

    private void requireVersionAndWaiting(RoomRow room, long expectedVersion) {
        if (!"WAITING".equals(room.state())) throw RoomFailure.conflict("房间已经进入对局交接，无法修改");
        if (room.version() != expectedVersion) throw RoomFailure.conflict("房间状态已更新，请刷新后重试");
    }

    private RoomRow memberRoomForUpdate(UUID id, UUID userId) {
        RoomRow room = lockedRoom(id);
        if (room == null || !room.expiresAt().isAfter(clock.instant()) || !isMember(id, userId)) throw RoomFailure.notFound();
        return room;
    }

    private RoomRow roomByCodeForUpdate(String code) {
        return single(jdbc.query("SELECT " + ROOM_COLUMNS + " FROM game.rooms WHERE code = ? FOR UPDATE", ROOM_MAPPER, code));
    }

    private RoomRow lockedRoom(UUID id) {
        return single(jdbc.query("SELECT " + ROOM_COLUMNS + " FROM game.rooms WHERE id = ? FOR UPDATE", ROOM_MAPPER, id));
    }

    private RoomRow room(UUID id) {
        return single(jdbc.query("SELECT " + ROOM_COLUMNS + " FROM game.rooms WHERE id = ?", ROOM_MAPPER, id));
    }

    private RoomRow requiredRoom(UUID id) {
        RoomRow found = room(id);
        if (found == null) throw RoomFailure.notFound();
        return found;
    }

    private UUID currentRoomId(UUID userId) {
        return single(jdbc.query("SELECT room_id FROM game.room_members WHERE user_id = ?", (rs, row) -> rs.getObject(1, UUID.class), userId));
    }

    private boolean isMember(UUID roomId, UUID userId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM game.room_members WHERE room_id = ? AND user_id = ?", Integer.class, roomId, userId);
        return count != null && count == 1;
    }

    private List<RoomView.Member> members(RoomRow room) {
        return jdbc.query("SELECT user_id, nickname, seat, ready FROM game.room_members WHERE room_id = ? ORDER BY seat",
                (rs, row) -> member(rs, room.mode()), room.id());
    }

    private RoomView.Member member(ResultSet rs, String mode) throws SQLException {
        int seat = rs.getInt("seat");
        return new RoomView.Member(rs.getObject("user_id", UUID.class), rs.getString("nickname"), seat,
                "TEAM_2V2".equals(mode) ? (seat % 2 == 0 ? "A" : "B") : null, rs.getBoolean("ready"));
    }

    private RoomView view(RoomRow room) {
        List<RoomView.Member> members = members(room);
        boolean allReady = members.stream().allMatch(RoomView.Member::ready);
        boolean canStart = "WAITING".equals(room.state()) && allReady &&
                ("CLASSIC".equals(room.mode()) ? members.size() >= 2 && members.size() <= room.maxPlayers()
                        : members.size() == 4 && members.stream().filter(member -> "A".equals(member.team())).count() == 2
                                && members.stream().filter(member -> "B".equals(member.team())).count() == 2);
        return new RoomView(room.id(), room.code(), room.mode(), room.maxPlayers(), room.hostUserId(),
                room.state(), room.version(), room.expiresAt(), canStart, members);
    }

    private int firstFreeSeat(List<RoomView.Member> members, int limit, String team) {
        for (int seat = 0; seat < limit; seat++) {
            if (team != null && !(team.equals(seat % 2 == 0 ? "A" : "B"))) continue;
            int candidate = seat;
            if (members.stream().noneMatch(member -> member.seat() == candidate)) return seat;
        }
        return -1;
    }

    private void validateSettings(String mode, int maxPlayers) {
        if (!("CLASSIC".equals(mode) && maxPlayers >= 2 && maxPlayers <= 6)
                && !("TEAM_2V2".equals(mode) && maxPlayers == 4)) throw RoomFailure.invalid();
    }

    private String newCode() {
        StringBuilder code = new StringBuilder(10);
        for (int i = 0; i < 10; i++) code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return code.toString();
    }

    private static <T> T single(List<T> values) { return values.isEmpty() ? null : values.get(0); }

    private record RoomRow(UUID id, String code, String mode, int maxPlayers, UUID hostUserId,
            String state, long version, Instant expiresAt) { }
}
