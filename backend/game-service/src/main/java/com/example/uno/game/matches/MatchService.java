package com.example.uno.game.matches;

import com.example.uno.core.rules.ClassicUno;
import com.example.uno.core.rules.TeamUno;
import com.example.uno.core.rules.UnoCard;
import com.example.uno.core.rules.UnoCommand;
import com.example.uno.core.rules.UnoRuleViolation;
import com.example.uno.core.rules.UnoSnapshot;
import com.example.uno.core.rules.UnoState;
import com.example.uno.core.rules.UnoTransition;
import com.example.uno.core.rules.UnoView;
import com.example.uno.game.auth.GameIdentity;
import com.example.uno.game.rooms.RoomService;
import java.sql.Timestamp;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class MatchService {
    private static final Duration ROOM_LIFETIME = Duration.ofHours(24);
    private static final Duration TURN_LIMIT = Duration.ofSeconds(30);
    private static final Duration DRAW_FOUR_LIMIT = Duration.ofSeconds(8);
    private static final Duration ROUND_BREAK_LIMIT = Duration.ofSeconds(120);
    private static final int MAX_MISSED_TURNS = 3;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final RoomService rooms;
    private final JsonMapper json = new JsonMapper();
    private final ClassicUno rules = new ClassicUno();
    private final TeamUno teamRules = new TeamUno();

    public MatchService(JdbcTemplate jdbc, Clock roomClock, RoomService rooms) {
        this.jdbc = jdbc;
        this.clock = roomClock;
        this.rooms = rooms;
    }

    @Transactional
    public MatchStart start(UUID roomId, GameIdentity identity, long expectedVersion) {
        Room room = one(jdbc.query("SELECT mode, state, version, host_user_id, expires_at FROM game.rooms WHERE id = ? FOR UPDATE",
                (rs, row) -> new Room(rs.getString("mode"), rs.getString("state"), rs.getLong("version"),
                        rs.getObject("host_user_id", UUID.class), rs.getTimestamp("expires_at").toInstant()), roomId));
        if (room == null) throw MatchFailure.notFound();
        if (!isRoomMember(roomId, identity.userId())) throw MatchFailure.notFound();
        if (!identity.userId().equals(room.hostUserId())) throw MatchFailure.forbidden();
        if ("PLAYING".equals(room.state())) {
            UUID active = one(jdbc.query("SELECT id FROM game.matches WHERE room_id = ? AND state = 'PLAYING'",
                    (rs, row) -> rs.getObject(1, UUID.class), roomId));
            if (active != null) {
                MatchState current = snapshot(active, identity);
                return new MatchStart(active, current.view(), room.version(), current.deadlineAt(), current.status());
            }
        }
        if (!("CLASSIC".equals(room.mode()) || "TEAM_2V2".equals(room.mode())) || !"WAITING".equals(room.state())
                || room.version() != expectedVersion || !room.expiresAt().isAfter(clock.instant()))
            throw MatchFailure.conflict();
        List<Member> members = jdbc.query("SELECT user_id, nickname, seat, ready FROM game.room_members WHERE room_id = ? ORDER BY seat",
                (rs, row) -> new Member(rs.getObject("user_id", UUID.class), rs.getString("nickname"), rs.getBoolean("ready")),
                roomId);
        if (("TEAM_2V2".equals(room.mode()) && members.size() != 4)
                || ("CLASSIC".equals(room.mode()) && (members.size() < 2 || members.size() > 6))
                || members.stream().anyMatch(member -> !member.ready()))
            throw MatchFailure.conflict();
        int dealerSeat = -1;
        for (int seat = 0; seat < members.size(); seat++) {
            if (members.get(seat).userId().equals(identity.userId())) dealerSeat = seat;
        }
        if (dealerSeat < 0) throw MatchFailure.conflict();
        UnoState initial = "TEAM_2V2".equals(room.mode())
                ? teamRules.start(members.stream().map(Member::userId).toList(), dealerSeat)
                : rules.start(members.stream().map(Member::userId).toList(), dealerSeat);
        UUID matchId = UUID.randomUUID();
        Instant deadline = newDeadline(initial.phase());
        jdbc.update("INSERT INTO game.matches(id, room_id, mode, state, rules_version, version, snapshot, deadline_at) "
                        + "VALUES (?, ?, ?, 'PLAYING', ?, ?, CAST(? AS jsonb), ?)",
                matchId, roomId, room.mode(), ClassicUno.RULES_VERSION, initial.version(),
                json.writeValueAsString(initial.snapshot()), timestamp(deadline));
        for (int seat = 0; seat < members.size(); seat++) jdbc.update(
                "INSERT INTO game.match_players(match_id, user_id, seat, nickname_snapshot, team_snapshot) VALUES (?, ?, ?, ?, ?)",
                matchId, members.get(seat).userId(), seat, members.get(seat).nickname(),
                "TEAM_2V2".equals(room.mode()) ? (seat % 2 == 0 ? "A" : "B") : null);
        jdbc.update("UPDATE game.rooms SET state = 'PLAYING', version = version + 1, expires_at = ? WHERE id = ?",
                Timestamp.from(clock.instant().plus(ROOM_LIFETIME)), roomId);
        return new MatchStart(matchId, rules.view(initial, identity.userId()), room.version() + 1, deadline, "PLAYING");
    }

    @Transactional(readOnly = true)
    public UnoView state(UUID matchId, GameIdentity identity) {
        return snapshot(matchId, identity).view();
    }

    @Transactional(readOnly = true)
    public MatchState snapshot(UUID matchId, GameIdentity identity) {
        StateRow row = one(jdbc.query("SELECT m.snapshot::text, m.deadline_at, m.state, m.interruption_reason FROM game.matches m "
                        + "JOIN game.match_players p ON p.match_id = m.id WHERE m.id = ? AND p.user_id = ?",
                (rs, index) -> new StateRow(rs.getString(1), instant(rs.getTimestamp(2)),
                        rs.getString(3), rs.getString(4)),
                matchId, identity.userId()));
        if (row == null) throw MatchFailure.notFound();
        return new MatchState(rules.view(UnoState.restore(json.readValue(row.snapshot(), UnoSnapshot.class)),
                identity.userId()), row.deadlineAt(), row.status(), row.interruptionReason());
    }

    @Transactional(readOnly = true)
    public MatchStart current(UUID roomId, GameIdentity identity) {
        ActiveMatch active = one(jdbc.query("SELECT m.id, r.version FROM game.matches m "
                        + "JOIN game.rooms r ON r.id = m.room_id "
                        + "JOIN game.match_players p ON p.match_id = m.id "
                        + "WHERE m.room_id = ? AND m.state = 'PLAYING' AND p.user_id = ?",
                (rs, row) -> new ActiveMatch(rs.getObject(1, UUID.class), rs.getLong(2)),
                roomId, identity.userId()));
        if (active == null) return null;
        MatchState current = snapshot(active.id(), identity);
        return new MatchStart(active.id(), current.view(), active.roomVersion(), current.deadlineAt(), current.status());
    }

    @Transactional
    public CommandResult command(UUID matchId, GameIdentity identity, MatchCommandInput input) {
        if (input.protocolVersion() != 1) throw MatchFailure.invalid();
        MatchRow match = locked(matchId);
        if (match == null || !isMatchPlayer(matchId, identity.userId())) throw MatchFailure.notFound();
        String requestPayload = json.writeValueAsString(input);
        StoredReceipt old = one(jdbc.query("SELECT request_payload, applied_version, event, outcome, "
                        + "cards_drawn::text, private_evidence::text FROM game.match_commands "
                        + "WHERE match_id = ? AND actor_user_id = ? AND command_id = ?",
                (rs, row) -> new StoredReceipt(rs.getString(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)),
                matchId, identity.userId(), input.commandId()));
        UnoState before = UnoState.restore(json.readValue(match.snapshot(), UnoSnapshot.class));
        if (old != null) {
            if (!old.requestPayload().equals(requestPayload)) throw MatchFailure.conflict();
            return new CommandResult(input.commandId(), true, old.appliedVersion(),
                    rules.view(before, identity.userId()), old.event(), old.outcome(),
                    drawn(old.cardsDrawn()), evidence(old.privateEvidence()), match.deadlineAt(), match.state());
        }
        if (!"PLAYING".equals(match.state()) || match.version() != input.expectedVersion())
            throw MatchFailure.conflict();
        if (match.deadlineAt() != null && !clock.instant().isBefore(match.deadlineAt()))
            throw MatchFailure.turnExpired();
        UnoState after;
        String event;
        String outcome = UnoTransition.ChallengeOutcome.NOT_APPLICABLE.name();
        Map<Integer, Integer> drawn = Map.of();
        List<UnoCard> evidence = List.of();
        try {
            if (input.type() == MatchCommandInput.Type.NEXT_ROUND) {
                if ("TEAM_2V2".equals(match.mode())) throw MatchFailure.invalid();
                requireEmptyPayload(input);
                after = rules.nextRound(before, new SecureRandom());
                event = "NEXT_ROUND_STARTED";
            } else {
                UnoTransition transition = apply(match.mode(), before, toRuleCommand(input, identity.userId()));
                after = transition.state();
                event = transition.event().name();
                outcome = transition.challengeOutcome().name();
                drawn = transition.cardsDrawnBySeat();
                evidence = transition.privateReveals().getOrDefault(identity.userId(), List.of());
            }
        } catch (UnoRuleViolation violation) {
            throw MatchFailure.rule(violation.code().name());
        }
        Instant deadline = nextDeadline(match.deadlineAt(), after.phase(), event);
        persist(matchId, match.roomId(), identity.userId(), input.commandId(), requestPayload,
                after, event, outcome, drawn, evidence, deadline, "PLAYER");
        return new CommandResult(input.commandId(), false, after.version(), rules.view(after, identity.userId()),
                event, outcome, drawn, evidence, deadline,
                after.phase() == UnoState.Phase.MATCH_OVER ? "ENDED" : "PLAYING");
    }

    /** A deliberate departure interrupts the casual match and removes the player from its room. */
    @Transactional
    public MatchState leave(UUID matchId, GameIdentity identity) {
        MatchRow match = locked(matchId);
        if (match == null || !isMatchPlayer(matchId, identity.userId())) throw MatchFailure.notFound();
        if (!"PLAYING".equals(match.state())) return snapshot(matchId, identity);
        UnoState before = UnoState.restore(json.readValue(match.snapshot(), UnoSnapshot.class));
        jdbc.update("UPDATE game.matches SET state = 'INTERRUPTED', ended_at = ?, deadline_at = NULL, "
                        + "interruption_reason = 'PLAYER_LEFT' WHERE id = ?",
                Timestamp.from(clock.instant()), matchId);
        jdbc.update("INSERT INTO game.match_commands(match_id, actor_user_id, command_id, request_payload, "
                        + "applied_version, event, outcome, cards_drawn, private_evidence, source) "
                        + "VALUES (?, ?, ?, ?, ?, 'MATCH_INTERRUPTED', 'NOT_APPLICABLE', "
                        + "'{}'::jsonb, '[]'::jsonb, 'PLAYER')",
                matchId, identity.userId(), UUID.randomUUID(), "{\"type\":\"PLAYER_LEFT\"}", before.version());
        finishRoom(matchId, match.roomId());
        if (match.roomId() != null) rooms.leave(match.roomId(), identity);
        return new MatchState(rules.view(before, identity.userId()), null, "INTERRUPTED", "PLAYER_LEFT");
    }

    /** The row lock and deadline recheck make concurrent workers and player commands resolve once. */
    @Transactional
    public TimeoutResult resolveTimeout(UUID matchId) {
        MatchRow match = locked(matchId);
        if (match == null || !"PLAYING".equals(match.state()) || match.deadlineAt() == null
                || clock.instant().isBefore(match.deadlineAt())) return null;
        UnoState before = UnoState.restore(json.readValue(match.snapshot(), UnoSnapshot.class));
        UUID actor = before.players().get(before.currentSeat());
        if (before.phase() == UnoState.Phase.TURN || before.phase() == UnoState.Phase.AFTER_DRAW
                || before.phase() == UnoState.Phase.INITIAL_WILD_COLOR) {
            Integer missed = jdbc.queryForObject("UPDATE game.match_players "
                    + "SET consecutive_timeouts = consecutive_timeouts + 1 "
                    + "WHERE match_id = ? AND user_id = ? RETURNING consecutive_timeouts",
                    Integer.class, matchId, actor);
            if (missed != null && missed >= MAX_MISSED_TURNS) {
                return interrupt(matchId, match.roomId(), actor, before);
            }
        }
        UnoTransition transition;
        Map<Integer, Integer> drawn;
        String event;
        try {
            switch (before.phase()) {
                case INITIAL_WILD_COLOR -> transition = apply(match.mode(), before,
                        new UnoCommand.ChooseInitialColor(actor, UnoCard.Color.RED));
                case TURN -> transition = apply(match.mode(), before, new UnoCommand.Draw(actor));
                case AFTER_DRAW -> transition = apply(match.mode(), before, new UnoCommand.Pass(actor));
                case DRAW_FOUR_RESPONSE -> transition = apply(match.mode(), before, new UnoCommand.AcceptDrawFour(actor));
                case ROUND_OVER -> {
                    if (!"CLASSIC".equals(match.mode())) throw new IllegalStateException("Team round cannot wait");
                    UnoState next = rules.nextRound(before, new SecureRandom());
                    transition = new UnoTransition(next, UnoTransition.Event.ROUND_STARTED,
                            Map.of(), UnoTransition.ChallengeOutcome.NOT_APPLICABLE, Map.of());
                }
                case MATCH_OVER -> {
                    jdbc.update("UPDATE game.matches SET deadline_at = NULL WHERE id = ?", matchId);
                    return null;
                }
                default -> throw new IllegalStateException("Unhandled timeout phase");
            }
            event = before.phase() == UnoState.Phase.ROUND_OVER
                    ? "AUTO_NEXT_ROUND_STARTED" : "AUTO_" + transition.event().name();
            drawn = transition.cardsDrawnBySeat();
            if (before.phase() == UnoState.Phase.TURN
                    && transition.state().phase() == UnoState.Phase.AFTER_DRAW) {
                transition = apply(match.mode(), transition.state(), new UnoCommand.Pass(actor));
                event = "AUTO_DREW_AND_PASSED";
            }
        } catch (UnoRuleViolation violation) {
            throw new IllegalStateException("Persisted match rejected its timeout action", violation);
        }
        UnoState after = transition.state();
        Instant deadline = newDeadline(after.phase());
        UUID commandId = UUID.randomUUID();
        persist(matchId, match.roomId(), actor, commandId,
                "{\"type\":\"TIMEOUT\",\"phase\":\"" + before.phase() + "\"}",
                after, event, transition.challengeOutcome().name(), drawn,
                List.of(), deadline, "TIMEOUT");
        return new TimeoutResult(matchId, after.version(), event);
    }

    private TimeoutResult interrupt(UUID matchId, UUID roomId, UUID actor, UnoState before) {
        Instant now = clock.instant();
        jdbc.update("UPDATE game.matches SET state = 'INTERRUPTED', ended_at = ?, deadline_at = NULL, "
                        + "interruption_reason = 'REPEATED_TURN_TIMEOUT' WHERE id = ?",
                Timestamp.from(now), matchId);
        jdbc.update("INSERT INTO game.match_commands(match_id, actor_user_id, command_id, request_payload, "
                        + "applied_version, event, outcome, cards_drawn, private_evidence, source) "
                        + "VALUES (?, ?, ?, ?, ?, 'MATCH_INTERRUPTED', 'NOT_APPLICABLE', "
                        + "'{}'::jsonb, '[]'::jsonb, 'TIMEOUT')",
                matchId, actor, UUID.randomUUID(), "{\"type\":\"TIMEOUT_INTERRUPT\"}", before.version());
        finishRoom(matchId, roomId);
        return new TimeoutResult(matchId, before.version(), "MATCH_INTERRUPTED");
    }

    @Transactional(readOnly = true)
    public List<UUID> dueMatches() {
        return jdbc.query("SELECT id FROM game.matches WHERE state = 'PLAYING' AND deadline_at <= ? "
                        + "ORDER BY deadline_at LIMIT 100",
                (rs, row) -> rs.getObject(1, UUID.class), Timestamp.from(clock.instant()));
    }

    private MatchRow locked(UUID matchId) {
        return one(jdbc.query("SELECT room_id, mode, state, version, snapshot::text, deadline_at "
                        + "FROM game.matches WHERE id = ? FOR UPDATE",
                (rs, row) -> new MatchRow(rs.getObject("room_id", UUID.class), rs.getString("mode"), rs.getString("state"),
                        rs.getLong("version"), rs.getString(5), instant(rs.getTimestamp(6))), matchId));
    }

    private UnoTransition apply(String mode, UnoState state, UnoCommand command) {
        return "TEAM_2V2".equals(mode) ? teamRules.apply(state, command) : rules.apply(state, command);
    }

    private void persist(UUID matchId, UUID roomId, UUID actor, UUID commandId, String requestPayload,
            UnoState after, String event, String outcome, Map<Integer, Integer> drawn,
            List<UnoCard> evidence, Instant deadline, String source) {
        List<Integer> evidenceIds = evidence.stream().map(UnoCard::id).toList();
        jdbc.update("UPDATE game.matches SET version = ?, snapshot = CAST(? AS jsonb), state = ?, "
                        + "ended_at = CAST(? AS timestamptz), deadline_at = ? WHERE id = ?",
                after.version(), json.writeValueAsString(after.snapshot()),
                after.phase() == UnoState.Phase.MATCH_OVER ? "ENDED" : "PLAYING",
                after.phase() == UnoState.Phase.MATCH_OVER ? Timestamp.from(clock.instant()) : null,
                timestamp(deadline), matchId);
        jdbc.update("INSERT INTO game.match_commands(match_id, actor_user_id, command_id, request_payload, "
                        + "applied_version, event, outcome, cards_drawn, private_evidence, source) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)",
                matchId, actor, commandId, requestPayload, after.version(), event, outcome,
                json.writeValueAsString(drawn), json.writeValueAsString(evidenceIds), source);
        if ("PLAYER".equals(source)) jdbc.update("UPDATE game.match_players SET consecutive_timeouts = 0 "
                + "WHERE match_id = ? AND user_id = ?", matchId, actor);
        if (after.phase() == UnoState.Phase.MATCH_OVER && roomId != null) {
            finishRoom(matchId, roomId);
        } else if (roomId != null) {
            jdbc.update("UPDATE game.rooms SET expires_at = GREATEST(expires_at, ?) WHERE id = ?",
                    Timestamp.from(clock.instant().plus(ROOM_LIFETIME)), roomId);
        }
    }

    private void finishRoom(UUID matchId, UUID roomId) {
        if (roomId == null) return;
        Instant now = clock.instant();
        jdbc.update("INSERT INTO game.voice_cleanup(match_id, voice_generation, next_attempt_at, retain_until) "
                        + "SELECT id, voice_generation, ?, ? FROM game.matches "
                        + "WHERE id = ? AND mode = 'TEAM_2V2' "
                        + "ON CONFLICT (match_id, voice_generation) DO NOTHING",
                Timestamp.from(now), Timestamp.from(now.plusSeconds(70)), matchId);
        jdbc.update("UPDATE game.room_members SET ready = FALSE WHERE room_id = ?", roomId);
        jdbc.update("UPDATE game.rooms SET state = 'WAITING', version = version + 1, expires_at = ? WHERE id = ?",
                Timestamp.from(now.plus(ROOM_LIFETIME)), roomId);
    }

    private Instant nextDeadline(Instant previous, UnoState.Phase phase, String event) {
        if (event.equals("UNO_DECLARED") || event.equals("UNO_CAUGHT")
                || (event.equals("DREW") && phase == UnoState.Phase.AFTER_DRAW)) return previous;
        return newDeadline(phase);
    }

    private Instant newDeadline(UnoState.Phase phase) {
        return switch (phase) {
            case INITIAL_WILD_COLOR, TURN, AFTER_DRAW -> clock.instant().plus(TURN_LIMIT);
            case DRAW_FOUR_RESPONSE -> clock.instant().plus(DRAW_FOUR_LIMIT);
            case ROUND_OVER -> clock.instant().plus(ROUND_BREAK_LIMIT);
            case MATCH_OVER -> null;
        };
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private UnoCommand toRuleCommand(MatchCommandInput input, UUID actor) {
        return switch (input.type()) {
            case PLAY -> {
                if (input.cardId() == null || input.targetUserId() != null) throw MatchFailure.invalid();
                yield new UnoCommand.Play(actor, input.cardId(), input.chosenColor(), input.callUno());
            }
            case CATCH_UNO -> {
                if (input.targetUserId() == null || input.cardId() != null || input.chosenColor() != null
                        || input.callUno()) throw MatchFailure.invalid();
                yield new UnoCommand.CatchUno(actor, input.targetUserId());
            }
            case CHOOSE_INITIAL_COLOR -> {
                if (input.chosenColor() == null || input.cardId() != null || input.targetUserId() != null
                        || input.callUno()) throw MatchFailure.invalid();
                yield new UnoCommand.ChooseInitialColor(actor, input.chosenColor());
            }
            case DRAW -> { requireEmptyPayload(input); yield new UnoCommand.Draw(actor); }
            case PASS -> { requireEmptyPayload(input); yield new UnoCommand.Pass(actor); }
            case SAY_UNO -> { requireEmptyPayload(input); yield new UnoCommand.SayUno(actor); }
            case ACCEPT_DRAW_FOUR -> { requireEmptyPayload(input); yield new UnoCommand.AcceptDrawFour(actor); }
            case CHALLENGE_DRAW_FOUR -> { requireEmptyPayload(input); yield new UnoCommand.ChallengeDrawFour(actor); }
            case NEXT_ROUND -> throw MatchFailure.invalid();
        };
    }

    private void requireEmptyPayload(MatchCommandInput input) {
        if (input.cardId() != null || input.chosenColor() != null || input.targetUserId() != null || input.callUno())
            throw MatchFailure.invalid();
    }

    private boolean isMatchPlayer(UUID matchId, UUID userId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM game.match_players WHERE match_id = ? AND user_id = ?",
                Integer.class, matchId, userId);
        return count != null && count == 1;
    }

    private Map<Integer, Integer> drawn(String jsonText) {
        Map<?, ?> raw = json.readValue(jsonText, Map.class);
        java.util.HashMap<Integer, Integer> result = new java.util.HashMap<>();
        raw.forEach((seat, count) -> result.put(Integer.parseInt(seat.toString()), ((Number) count).intValue()));
        return Map.copyOf(result);
    }

    private List<UnoCard> evidence(String jsonText) {
        List<?> ids = json.readValue(jsonText, List.class);
        return ids.stream().map(id -> UnoCard.of(((Number) id).intValue())).toList();
    }

    private boolean isRoomMember(UUID roomId, UUID userId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM game.room_members WHERE room_id = ? AND user_id = ?",
                Integer.class, roomId, userId);
        return count != null && count == 1;
    }

    private static <T> T one(List<T> rows) { return rows.isEmpty() ? null : rows.get(0); }

    public record MatchStart(UUID matchId, UnoView view, long roomVersion, Instant deadlineAt, String status) { }
    public record MatchState(UnoView view, Instant deadlineAt, String status, String interruptionReason) { }
    public record CommandResult(UUID commandId, boolean duplicate, long appliedVersion, UnoView view,
            String event, String challengeOutcome, Map<Integer, Integer> cardsDrawnBySeat,
            List<UnoCard> privateChallengeEvidence, Instant deadlineAt, String status) { }
    public record TimeoutResult(UUID matchId, long appliedVersion, String event) { }
    private record Room(String mode, String state, long version, UUID hostUserId, java.time.Instant expiresAt) { }
    private record Member(UUID userId, String nickname, boolean ready) { }
    private record MatchRow(UUID roomId, String mode, String state, long version, String snapshot, Instant deadlineAt) { }
    private record StateRow(String snapshot, Instant deadlineAt, String status, String interruptionReason) { }
    private record StoredReceipt(String requestPayload, long appliedVersion, String event, String outcome,
            String cardsDrawn, String privateEvidence) { }
    private record ActiveMatch(UUID id, long roomVersion) { }
}
