package com.example.uno.game.matches;

import com.example.uno.core.rules.ClassicUno;
import com.example.uno.core.rules.UnoCard;
import com.example.uno.core.rules.UnoCommand;
import com.example.uno.core.rules.UnoRuleViolation;
import com.example.uno.core.rules.UnoSnapshot;
import com.example.uno.core.rules.UnoState;
import com.example.uno.core.rules.UnoTransition;
import com.example.uno.core.rules.UnoView;
import com.example.uno.game.auth.GameIdentity;
import java.sql.Timestamp;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
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
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final JsonMapper json = new JsonMapper();
    private final ClassicUno rules = new ClassicUno();

    public MatchService(JdbcTemplate jdbc, Clock roomClock) {
        this.jdbc = jdbc;
        this.clock = roomClock;
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
            if (active != null) return new MatchStart(active, state(active, identity), room.version());
        }
        if (!"CLASSIC".equals(room.mode()) || !"WAITING".equals(room.state())
                || room.version() != expectedVersion || !room.expiresAt().isAfter(clock.instant()))
            throw MatchFailure.conflict();
        List<Member> members = jdbc.query("SELECT user_id, seat, ready FROM game.room_members WHERE room_id = ? ORDER BY seat",
                (rs, row) -> new Member(rs.getObject("user_id", UUID.class), rs.getBoolean("ready")),
                roomId);
        if (members.size() < 2 || members.size() > 6 || members.stream().anyMatch(member -> !member.ready()))
            throw MatchFailure.conflict();
        int dealerSeat = -1;
        for (int seat = 0; seat < members.size(); seat++) {
            if (members.get(seat).userId().equals(identity.userId())) dealerSeat = seat;
        }
        if (dealerSeat < 0) throw MatchFailure.conflict();
        UnoState initial = rules.start(members.stream().map(Member::userId).toList(), dealerSeat);
        UUID matchId = UUID.randomUUID();
        jdbc.update("INSERT INTO game.matches(id, room_id, mode, state, rules_version, version, snapshot) "
                        + "VALUES (?, ?, 'CLASSIC', 'PLAYING', ?, ?, CAST(? AS jsonb))",
                matchId, roomId, ClassicUno.RULES_VERSION, initial.version(), json.writeValueAsString(initial.snapshot()));
        for (int seat = 0; seat < members.size(); seat++) jdbc.update(
                "INSERT INTO game.match_players(match_id, user_id, seat) VALUES (?, ?, ?)",
                matchId, members.get(seat).userId(), seat);
        jdbc.update("UPDATE game.rooms SET state = 'PLAYING', version = version + 1, expires_at = ? WHERE id = ?",
                Timestamp.from(clock.instant().plus(ROOM_LIFETIME)), roomId);
        return new MatchStart(matchId, rules.view(initial, identity.userId()), room.version() + 1);
    }

    @Transactional(readOnly = true)
    public UnoView state(UUID matchId, GameIdentity identity) {
        String snapshot = one(jdbc.query("SELECT m.snapshot::text FROM game.matches m "
                        + "JOIN game.match_players p ON p.match_id = m.id WHERE m.id = ? AND p.user_id = ?",
                (rs, row) -> rs.getString(1), matchId, identity.userId()));
        if (snapshot == null) throw MatchFailure.notFound();
        return rules.view(UnoState.restore(json.readValue(snapshot, UnoSnapshot.class)), identity.userId());
    }

    @Transactional(readOnly = true)
    public MatchStart current(UUID roomId, GameIdentity identity) {
        ActiveMatch active = one(jdbc.query("SELECT m.id, r.version FROM game.matches m "
                        + "JOIN game.rooms r ON r.id = m.room_id "
                        + "JOIN game.match_players p ON p.match_id = m.id "
                        + "WHERE m.room_id = ? AND m.state = 'PLAYING' AND p.user_id = ?",
                (rs, row) -> new ActiveMatch(rs.getObject(1, UUID.class), rs.getLong(2)),
                roomId, identity.userId()));
        return active == null ? null : new MatchStart(active.id(), state(active.id(), identity), active.roomVersion());
    }

    @Transactional
    public CommandResult command(UUID matchId, GameIdentity identity, MatchCommandInput input) {
        if (input.protocolVersion() != 1) throw MatchFailure.invalid();
        MatchRow match = one(jdbc.query("SELECT room_id, state, version, snapshot::text FROM game.matches WHERE id = ? FOR UPDATE",
                (rs, row) -> new MatchRow(rs.getObject("room_id", UUID.class), rs.getString("state"),
                        rs.getLong("version"), rs.getString(4)), matchId));
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
                    drawn(old.cardsDrawn()), evidence(old.privateEvidence()));
        }
        if (!"PLAYING".equals(match.state()) || match.version() != input.expectedVersion())
            throw MatchFailure.conflict();
        UnoState after;
        String event;
        String outcome = UnoTransition.ChallengeOutcome.NOT_APPLICABLE.name();
        Map<Integer, Integer> drawn = Map.of();
        List<UnoCard> evidence = List.of();
        try {
            if (input.type() == MatchCommandInput.Type.NEXT_ROUND) {
                requireEmptyPayload(input);
                after = rules.nextRound(before, new SecureRandom());
                event = "NEXT_ROUND_STARTED";
            } else {
                UnoTransition transition = rules.apply(before, toRuleCommand(input, identity.userId()));
                after = transition.state();
                event = transition.event().name();
                outcome = transition.challengeOutcome().name();
                drawn = transition.cardsDrawnBySeat();
                evidence = transition.privateReveals().getOrDefault(identity.userId(), List.of());
            }
        } catch (UnoRuleViolation violation) {
            throw MatchFailure.rule(violation.code().name());
        }
        List<Integer> evidenceIds = evidence.stream().map(UnoCard::id).toList();
        jdbc.update("UPDATE game.matches SET version = ?, snapshot = CAST(? AS jsonb), state = ?, "
                        + "ended_at = CAST(? AS timestamptz) WHERE id = ?",
                after.version(), json.writeValueAsString(after.snapshot()),
                after.phase() == UnoState.Phase.MATCH_OVER ? "ENDED" : "PLAYING",
                after.phase() == UnoState.Phase.MATCH_OVER ? Timestamp.from(clock.instant()) : null, matchId);
        jdbc.update("INSERT INTO game.match_commands(match_id, actor_user_id, command_id, request_payload, "
                        + "applied_version, event, outcome, cards_drawn, private_evidence) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))",
                matchId, identity.userId(), input.commandId(), requestPayload, after.version(), event, outcome,
                json.writeValueAsString(drawn), json.writeValueAsString(evidenceIds));
        if (after.phase() == UnoState.Phase.MATCH_OVER && match.roomId() != null) {
            jdbc.update("UPDATE game.room_members SET ready = FALSE WHERE room_id = ?", match.roomId());
            jdbc.update("UPDATE game.rooms SET state = 'WAITING', version = version + 1, expires_at = ? WHERE id = ?",
                    Timestamp.from(clock.instant().plus(ROOM_LIFETIME)), match.roomId());
        }
        return new CommandResult(input.commandId(), false, after.version(), rules.view(after, identity.userId()),
                event, outcome, drawn, evidence);
    }

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

    public record MatchStart(UUID matchId, UnoView view, long roomVersion) { }
    public record CommandResult(UUID commandId, boolean duplicate, long appliedVersion, UnoView view,
            String event, String challengeOutcome, Map<Integer, Integer> cardsDrawnBySeat,
            List<UnoCard> privateChallengeEvidence) { }
    private record Room(String mode, String state, long version, UUID hostUserId, java.time.Instant expiresAt) { }
    private record Member(UUID userId, boolean ready) { }
    private record MatchRow(UUID roomId, String state, long version, String snapshot) { }
    private record StoredReceipt(String requestPayload, long appliedVersion, String event, String outcome,
            String cardsDrawn, String privateEvidence) { }
    private record ActiveMatch(UUID id, long roomVersion) { }
}
