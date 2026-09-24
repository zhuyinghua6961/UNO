package com.example.uno.game.matches;

import static org.junit.jupiter.api.Assertions.*;

import com.example.uno.game.GameApplication;
import com.example.uno.game.auth.GameIdentity;
import com.example.uno.game.rooms.RoomFailure;
import com.example.uno.game.rooms.RoomService;
import com.example.uno.game.rooms.RoomView;
import com.example.uno.core.rules.UnoCard;
import com.example.uno.core.rules.UnoSnapshot;
import com.example.uno.core.rules.UnoState;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class MatchServiceIT {
    @Container
    static final PostgreSQLContainer database = new PostgreSQLContainer(DockerImageName.parse(
            System.getProperty("uno.postgres.image", "postgres:17-alpine"))
            .asCompatibleSubstituteFor("postgres"));
    private static ConfigurableApplicationContext application;
    private static MatchService matches;
    private static RoomService rooms;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        application = new SpringApplicationBuilder(GameApplication.class).run(
                "--server.port=0", "--spring.datasource.url=" + database.getJdbcUrl(),
                "--spring.datasource.username=" + database.getUsername(),
                "--spring.datasource.password=" + database.getPassword(),
                "--uno.matches.deadline-worker-enabled=false");
        matches = application.getBean(MatchService.class);
        rooms = application.getBean(RoomService.class);
        jdbc = application.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void stop() { if (application != null) application.close(); }

    @BeforeEach
    void empty() { jdbc.update("TRUNCATE game.voice_cleanup, game.voice_token_issuance, game.match_commands, "
            + "game.match_players, game.matches, game.room_members, game.rooms"); }

    @Test
    void readyClassicRoomStartsOnceAndRestoresSeparatePrivateViews() {
        GameIdentity host = player("Host");
        GameIdentity guest = player("Guest");
        GameIdentity outsider = player("Outsider");
        RoomView room = rooms.create(host, "CLASSIC", 2);
        room = rooms.join(guest, room.code());
        UUID roomId = room.id();
        long oldVersion = room.version();
        assertEquals("MATCH_FORBIDDEN", assertThrows(MatchFailure.class,
                () -> matches.start(roomId, guest, oldVersion)).code());
        assertEquals("MATCH_CONFLICT", assertThrows(MatchFailure.class,
                () -> matches.start(roomId, host, oldVersion)).code());
        room = rooms.ready(roomId, host, true, room.version());
        room = rooms.ready(roomId, guest, true, room.version());
        long readyVersion = room.version();
        assertEquals("MATCH_CONFLICT", assertThrows(MatchFailure.class,
                () -> matches.start(roomId, host, oldVersion)).code());

        MatchService.MatchStart started = matches.start(roomId, host, readyVersion);
        assertEquals(started.matchId(), matches.start(roomId, host, readyVersion).matchId());
        assertEquals(started.matchId(), matches.current(roomId, guest).matchId());
        assertNull(matches.current(roomId, outsider));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM game.matches", Integer.class));
        assertEquals("PLAYING", rooms.get(roomId, host).state());
        assertEquals(readyVersion + 1, started.roomVersion());
        assertEquals("ROOM_CONFLICT", assertThrows(RoomFailure.class,
                () -> rooms.leave(roomId, guest)).code());

        var hostView = matches.state(started.matchId(), host);
        var guestView = matches.state(started.matchId(), guest);
        assertEquals(started.view(), hostView);
        assertTrue(hostView.ownHand().size() >= 7);
        assertTrue(guestView.ownHand().size() >= 7);
        assertNotEquals(hostView.ownHand(), guestView.ownHand());
        assertEquals(guestView.ownHand().size(), hostView.players().get(1).handCount());
        assertEquals(hostView.ownHand().size(), guestView.players().get(0).handCount());
        assertEquals("MATCH_NOT_FOUND", assertThrows(MatchFailure.class,
                () -> matches.state(started.matchId(), outsider)).code());
        String responseJson = new JsonMapper().writeValueAsString(hostView);
        assertTrue(responseJson.contains("\"ownHand\""));
        assertTrue(responseJson.contains("\"id\""));
        assertFalse(responseJson.contains("drawPile"));

        GameIdentity actor = started.view().players().get(started.view().currentSeat()).userId().equals(host.userId())
                ? host : guest;
        boolean openingWild = started.view().phase() == UnoState.Phase.INITIAL_WILD_COLOR;
        MatchCommandInput action = new MatchCommandInput(1, UUID.randomUUID(), started.view().version(),
                openingWild ? MatchCommandInput.Type.CHOOSE_INITIAL_COLOR : MatchCommandInput.Type.DRAW,
                null, openingWild ? UnoCard.Color.RED : null, null, false);
        var applied = matches.command(started.matchId(), actor, action);
        assertFalse(applied.duplicate());
        assertEquals(2, applied.appliedVersion());
        var repeated = matches.command(started.matchId(), actor, action);
        assertTrue(repeated.duplicate());
        assertEquals(2, repeated.appliedVersion());
        assertEquals(applied.view(), repeated.view());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM game.match_commands", Integer.class));
        MatchCommandInput reusedId = new MatchCommandInput(1, action.commandId(), 1,
                MatchCommandInput.Type.PASS, null, null, null, false);
        assertEquals("MATCH_CONFLICT", assertThrows(MatchFailure.class,
                () -> matches.command(started.matchId(), actor, reusedId)).code());
        MatchCommandInput stale = new MatchCommandInput(1, UUID.randomUUID(), 1,
                MatchCommandInput.Type.DRAW, null, null, null, false);
        assertEquals("MATCH_CONFLICT", assertThrows(MatchFailure.class,
                () -> matches.command(started.matchId(), actor, stale)).code());
        assertEquals("MATCH_NOT_FOUND", assertThrows(MatchFailure.class,
                () -> matches.command(started.matchId(), outsider, stale)).code());
    }

    @Test
    void teamMatchSnapshotsSeatsAndFinishesForBothTeammates() {
        GameIdentity host = player("Host");
        GameIdentity b = player("B");
        GameIdentity partner = player("Partner");
        GameIdentity d = player("D");
        RoomView room = rooms.create(host, "TEAM_2V2", 4);
        room = rooms.join(b, room.code());
        room = rooms.join(partner, room.code());
        room = rooms.join(d, room.code());
        UUID roomId = room.id();
        long unreadyVersion = room.version();
        assertEquals(List.of("A", "B", "A", "B"), room.members().stream().map(RoomView.Member::team).toList());
        assertEquals("MATCH_CONFLICT", assertThrows(MatchFailure.class,
                () -> matches.start(roomId, host, unreadyVersion)).code());
        for (GameIdentity player : List.of(host, b, partner, d))
            room = rooms.ready(room.id(), player, true, room.version());
        var started = matches.start(room.id(), host, room.version());
        long playingVersion = room.version() + 1;
        UUID matchId = started.matchId();
        assertEquals("TEAM_2V2", jdbc.queryForObject("SELECT mode FROM game.matches WHERE id = ?", String.class, matchId));
        assertEquals(List.of("A", "B", "A", "B"), jdbc.query(
                "SELECT team_snapshot FROM game.match_players WHERE match_id = ? ORDER BY seat",
                (rs, row) -> rs.getString(1), matchId));
        assertEquals("ROOM_CONFLICT", assertThrows(RoomFailure.class,
                () -> rooms.selectTeam(roomId, partner, "B", playingVersion)).code());
        var partnerView = matches.state(matchId, partner);
        assertEquals(7, partnerView.ownHand().size());
        assertEquals(7, partnerView.players().get(0).handCount());

        JsonMapper json = new JsonMapper();
        UnoSnapshot old = json.readValue(jdbc.queryForObject(
                "SELECT snapshot::text FROM game.matches WHERE id = ?", String.class, matchId), UnoSnapshot.class);
        List<List<Integer>> hands = new ArrayList<>();
        old.hands().forEach(hand -> hands.add(new ArrayList<>(hand)));
        List<Integer> pile = new ArrayList<>(old.drawPile());
        UnoCard.Color activeColor = old.activeColor() == null ? UnoCard.Color.RED : old.activeColor();
        int card = hands.get(0).stream().filter(id -> UnoCard.of(id).color() == activeColor)
                .findFirst().orElse(-1);
        if (card < 0) {
            card = pile.stream().filter(id -> UnoCard.of(id).color() == activeColor).findFirst().orElseThrow();
            pile.remove(Integer.valueOf(card));
            pile.addAll(hands.get(0));
        } else {
            int finishingCard = card;
            hands.get(0).stream().filter(id -> id != finishingCard).forEach(pile::add);
        }
        hands.set(0, List.of(card));
        UnoSnapshot prepared = new UnoSnapshot(old.players(), hands, pile, old.discardPile(), old.scores(),
                old.dealerSeat(), 0, old.direction(), old.roundNumber(), old.version(),
                UnoState.Phase.TURN, activeColor, null, null, null, null, 0);
        UnoState.restore(prepared);
        jdbc.update("UPDATE game.matches SET snapshot = CAST(? AS jsonb) WHERE id = ?",
                json.writeValueAsString(prepared), matchId);
        assertEquals("INVALID_MATCH_INPUT", assertThrows(MatchFailure.class,
                () -> matches.command(matchId, host, new MatchCommandInput(1, UUID.randomUUID(), 1,
                        MatchCommandInput.Type.NEXT_ROUND, null, null, null, false))).code());
        var finished = matches.command(matchId, host, new MatchCommandInput(1, UUID.randomUUID(), 1,
                MatchCommandInput.Type.PLAY, card, null, null, false));
        assertEquals(UnoState.Phase.MATCH_OVER, finished.view().phase());
        assertEquals(0, finished.view().roundWinnerSeat());
        assertEquals(finished.view().players().get(0).score(), finished.view().players().get(2).score());
        assertEquals("ENDED", jdbc.queryForObject("SELECT state FROM game.matches WHERE id = ?", String.class, matchId));
        assertEquals("WAITING", rooms.get(room.id(), host).state());
        assertEquals(UnoState.Phase.MATCH_OVER, matches.state(matchId, partner).phase());
        MatchHistoryService history = application.getBean(MatchHistoryService.class);
        assertEquals("WIN", history.history(host, null, 20).items().get(0).result());
        assertEquals("WIN", history.history(partner, null, 20).items().get(0).result());
        assertEquals("LOSS", history.history(b, null, 20).items().get(0).result());
        assertEquals("LOSS", history.history(d, null, 20).items().get(0).result());
        assertEquals("TEAM_2V2", history.history(partner, null, 20).items().get(0).mode());
        assertEquals(new MatchHistoryService.ModeStats(1, 0, 0), history.stats(partner).team2v2());
        assertEquals(new MatchHistoryService.ModeStats(0, 1, 0), history.stats(b).team2v2());
    }

    @Test
    void seatsAreCompactForGameAfterSomeoneLeavesWaitingRoom() {
        GameIdentity host = player("Host");
        GameIdentity leaver = player("Leaver");
        GameIdentity guest = player("Guest");
        RoomView room = rooms.create(host, "CLASSIC", 3);
        room = rooms.join(leaver, room.code());
        room = rooms.join(guest, room.code());
        rooms.leave(room.id(), leaver);
        room = rooms.get(room.id(), host);
        assertEquals(2, room.members().get(1).seat());
        room = rooms.ready(room.id(), host, true, room.version());
        room = rooms.ready(room.id(), guest, true, room.version());
        var started = matches.start(room.id(), host, room.version());
        assertEquals(1, matches.state(started.matchId(), guest).players().get(1).seat());
    }

    @Test
    void completedHistoryIsPrivateStableAndPagedFromSavedMatchData() {
        GameIdentity host = player("Original Host");
        GameIdentity guest = player("Original Guest");
        GameIdentity outsider = player("Outsider");
        UUID firstId = startMatch(host, guest).matchId();
        JsonMapper json = new JsonMapper();
        UnoSnapshot original = json.readValue(jdbc.queryForObject(
                "SELECT snapshot::text FROM game.matches WHERE id = ?", String.class, firstId), UnoSnapshot.class);
        int hostSeat = original.players().indexOf(host.userId());
        int guestSeat = original.players().indexOf(guest.userId());
        List<List<Integer>> hands = new ArrayList<>();
        original.hands().forEach(hand -> hands.add(new ArrayList<>(hand)));
        List<Integer> pile = new ArrayList<>(original.drawPile());
        pile.addAll(hands.get(hostSeat));
        hands.get(hostSeat).clear();
        List<Integer> scores = new ArrayList<>(original.scores());
        scores.set(hostSeat, 500);
        UnoSnapshot finalState = new UnoSnapshot(original.players(), hands, pile,
                original.discardPile(), scores, original.dealerSeat(), hostSeat,
                original.direction(), original.roundNumber(), 2, UnoState.Phase.MATCH_OVER,
                original.activeColor(), null, null, null, hostSeat, 45);
        Instant firstEnd = Instant.parse("2026-09-23T08:00:00Z");
        jdbc.update("UPDATE game.matches SET snapshot = CAST(? AS jsonb), state = 'ENDED', "
                        + "version = 2, ended_at = ? WHERE id = ?",
                json.writeValueAsString(finalState), Timestamp.from(firstEnd), firstId);
        UUID secondId = UUID.randomUUID();
        UnoSnapshot guestWon = new UnoSnapshot(finalState.players(), finalState.hands(), finalState.drawPile(),
                finalState.discardPile(), finalState.scores(), finalState.dealerSeat(), guestSeat,
                finalState.direction(), finalState.roundNumber(), 2, UnoState.Phase.MATCH_OVER,
                finalState.activeColor(), null, null, null, guestSeat, 36);
        jdbc.update("INSERT INTO game.matches(id, mode, state, rules_version, version, snapshot, ended_at) "
                        + "VALUES (?, 'CLASSIC', 'ENDED', 1, 2, CAST(? AS jsonb), ?)",
                secondId, json.writeValueAsString(guestWon), Timestamp.from(firstEnd.plusSeconds(1)));
        jdbc.update("INSERT INTO game.match_players(match_id, user_id, seat, nickname_snapshot) VALUES (?, ?, ?, ?)",
                secondId, host.userId(), hostSeat, host.nickname());
        jdbc.update("INSERT INTO game.match_players(match_id, user_id, seat, nickname_snapshot) VALUES (?, ?, ?, ?)",
                secondId, guest.userId(), guestSeat, guest.nickname());

        MatchHistoryService history = application.getBean(MatchHistoryService.class);
        var firstPage = history.history(host, null, 1);
        assertEquals(1, firstPage.items().size());
        assertEquals(secondId, firstPage.items().get(0).matchId());
        assertEquals("LOSS", firstPage.items().get(0).result());
        assertEquals("Original Host", firstPage.items().get(0).players().get(hostSeat).nickname());
        assertNotNull(firstPage.nextCursor());
        var secondPage = history.history(host, firstPage.nextCursor(), 1);
        assertEquals(firstId, secondPage.items().get(0).matchId());
        assertEquals("WIN", secondPage.items().get(0).result());
        assertNull(secondPage.nextCursor());
        assertEquals("WIN", history.history(guest, null, 1).items().get(0).result());
        assertTrue(history.history(outsider, null, 20).items().isEmpty());
        assertEquals(new MatchHistoryService.ModeStats(1, 1, 0), history.stats(host).classic());
        assertEquals(new MatchHistoryService.ModeStats(0, 0, 0), history.stats(host).team2v2());
        assertEquals(new MatchHistoryService.ModeStats(0, 0, 0), history.stats(outsider).classic());
        assertEquals("INVALID_MATCH_INPUT", assertThrows(MatchFailure.class,
                () -> history.history(host, "invalid-cursor", 20)).code());
        assertFalse(json.writeValueAsString(firstPage).contains("ownHand"));
    }

    @Test
    void expiredTurnDrawsOnceAndEndsEvenWhenTheCardCouldBePlayed() throws Exception {
        GameIdentity host = player("Host");
        GameIdentity guest = player("Guest");
        var started = startMatch(host, guest);
        UUID matchId = started.matchId();
        JsonMapper json = new JsonMapper();
        UnoSnapshot old = json.readValue(jdbc.queryForObject(
                "SELECT snapshot::text FROM game.matches WHERE id = ?", String.class, matchId), UnoSnapshot.class);
        List<List<Integer>> hands = new ArrayList<>();
        old.hands().forEach(hand -> hands.add(new ArrayList<>(hand)));
        List<Integer> pile = new ArrayList<>(old.drawPile());
        UnoCard.Color activeColor = old.activeColor() == null ? UnoCard.Color.RED : old.activeColor();
        int last = pile.size() - 1;
        int playable = -1;
        for (int index = 0; index < pile.size(); index++) {
            UnoCard card = UnoCard.of(pile.get(index));
            if (card.color() == null || card.color() == activeColor) { playable = index; break; }
        }
        assertTrue(playable >= 0);
        java.util.Collections.swap(pile, playable, last);
        UnoSnapshot prepared = new UnoSnapshot(old.players(), hands, pile, old.discardPile(), old.scores(),
                old.dealerSeat(), old.currentSeat(), old.direction(), old.roundNumber(), old.version(),
                UnoState.Phase.TURN, activeColor, null, null, old.unoVulnerableSeat(), null, 0);
        UnoState.restore(prepared);
        jdbc.update("UPDATE game.matches SET snapshot = CAST(? AS jsonb), deadline_at = ? WHERE id = ?",
                json.writeValueAsString(prepared), Timestamp.from(Instant.now().minusSeconds(1)), matchId);
        GameIdentity actor = prepared.players().get(prepared.currentSeat()).equals(host.userId()) ? host : guest;
        MatchCommandInput late = new MatchCommandInput(1, UUID.randomUUID(), 1,
                MatchCommandInput.Type.DRAW, null, null, null, false);
        assertEquals("TURN_EXPIRED", assertThrows(MatchFailure.class,
                () -> matches.command(matchId, actor, late)).code());

        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> matches.resolveTimeout(matchId));
            var second = pool.submit(() -> matches.resolveTimeout(matchId));
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertEquals(1, (a == null ? 0 : 1) + (b == null ? 0 : 1));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(3, matches.state(matchId, actor).version());
        assertEquals(UnoState.Phase.TURN, matches.state(matchId, actor).phase());
        assertEquals("AUTO_DREW_AND_PASSED", jdbc.queryForObject(
                "SELECT event FROM game.match_commands WHERE match_id = ?", String.class, matchId));
        assertEquals("1", jdbc.queryForObject("SELECT cards_drawn->> ? FROM game.match_commands WHERE match_id = ?",
                String.class, Integer.toString(prepared.currentSeat()), matchId));
        assertNotNull(matches.snapshot(matchId, actor).deadlineAt());
        assertNull(matches.resolveTimeout(matchId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM game.match_commands WHERE source = 'TIMEOUT'", Integer.class));
    }

    @Test
    void acceptedCommandCanBeRetriedAfterItsDeadline() {
        GameIdentity host = player("Host");
        GameIdentity guest = player("Guest");
        var started = startMatch(host, guest);
        UUID matchId = started.matchId();
        GameIdentity actor = started.view().players().get(started.view().currentSeat()).userId().equals(host.userId())
                ? host : guest;
        boolean wild = started.view().phase() == UnoState.Phase.INITIAL_WILD_COLOR;
        MatchCommandInput command = new MatchCommandInput(1, UUID.randomUUID(), 1,
                wild ? MatchCommandInput.Type.CHOOSE_INITIAL_COLOR : MatchCommandInput.Type.DRAW,
                null, wild ? UnoCard.Color.RED : null, null, false);
        var receipt = matches.command(matchId, actor, command);
        jdbc.update("UPDATE game.matches SET deadline_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), matchId);
        assertTrue(matches.command(matchId, actor, command).duplicate());
        assertEquals(receipt.appliedVersion(), matches.command(matchId, actor, command).appliedVersion());
        assertNotNull(matches.resolveTimeout(matchId));
    }

    @Test
    void repeatedMissedTurnsInterruptWithoutAwardingVictoryAndFreeTheRoom() {
        GameIdentity host = player("Host");
        GameIdentity guest = player("Guest");
        var started = startMatch(host, guest);
        UUID matchId = started.matchId();
        UUID roomId = jdbc.queryForObject("SELECT room_id FROM game.matches WHERE id = ?", UUID.class, matchId);
        int resolved = 0;
        while ("PLAYING".equals(jdbc.queryForObject("SELECT state FROM game.matches WHERE id = ?", String.class, matchId))
                && resolved < 10) {
            jdbc.update("UPDATE game.matches SET deadline_at = ? WHERE id = ?",
                    Timestamp.from(Instant.now().minusSeconds(1)), matchId);
            assertNotNull(matches.resolveTimeout(matchId));
            resolved++;
        }
        assertTrue(resolved <= 6, "one of two seats must miss three turns within six timed actions");
        assertEquals("INTERRUPTED", matches.snapshot(matchId, host).status());
        assertNull(matches.snapshot(matchId, host).deadlineAt());
        assertEquals("REPEATED_TURN_TIMEOUT", jdbc.queryForObject(
                "SELECT interruption_reason FROM game.matches WHERE id = ?", String.class, matchId));
        assertEquals("WAITING", rooms.get(roomId, host).state());
        assertNull(matches.current(roomId, host));
        assertNull(matches.resolveTimeout(matchId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM game.match_commands "
                + "WHERE match_id = ? AND event = 'MATCH_INTERRUPTED'", Integer.class, matchId));
        var history = application.getBean(MatchHistoryService.class).history(host, null, 20).items().get(0);
        assertEquals("INTERRUPTED", history.result());
        assertNull(history.winnerUserId());
        assertEquals(new MatchHistoryService.ModeStats(0, 0, 1),
                application.getBean(MatchHistoryService.class).stats(host).classic());
        assertEquals("MATCH_CONFLICT", assertThrows(MatchFailure.class,
                () -> matches.command(matchId, host, new MatchCommandInput(1, UUID.randomUUID(),
                        started.view().version(), MatchCommandInput.Type.DRAW, null, null, null, false))).code());
    }

    @Test
    void aRealPlayerActionResetsOnlyThatPlayersMissedTurnCount() {
        GameIdentity host = player("Host");
        GameIdentity guest = player("Guest");
        var started = startMatch(host, guest);
        UUID matchId = started.matchId();
        UUID firstActor = started.view().players().get(started.view().currentSeat()).userId();
        jdbc.update("UPDATE game.matches SET deadline_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), matchId);
        matches.resolveTimeout(matchId);
        assertEquals(1, jdbc.queryForObject("SELECT consecutive_timeouts FROM game.match_players "
                + "WHERE match_id = ? AND user_id = ?", Integer.class, matchId, firstActor));
        for (int attempts = 0; attempts < 3; attempts++) {
            var view = matches.snapshot(matchId, host).view();
            if (view.players().get(view.currentSeat()).userId().equals(firstActor)) break;
            jdbc.update("UPDATE game.matches SET deadline_at = ? WHERE id = ?",
                    Timestamp.from(Instant.now().minusSeconds(1)), matchId);
            matches.resolveTimeout(matchId);
        }
        var view = matches.snapshot(matchId, host).view();
        assertEquals(firstActor, view.players().get(view.currentSeat()).userId());
        GameIdentity actor = firstActor.equals(host.userId()) ? host : guest;
        MatchCommandInput.Type action = switch (view.phase()) {
            case INITIAL_WILD_COLOR -> MatchCommandInput.Type.CHOOSE_INITIAL_COLOR;
            case AFTER_DRAW -> MatchCommandInput.Type.PASS;
            default -> MatchCommandInput.Type.DRAW;
        };
        matches.command(matchId, actor, new MatchCommandInput(1, UUID.randomUUID(), view.version(),
                action, null, action == MatchCommandInput.Type.CHOOSE_INITIAL_COLOR ? UnoCard.Color.RED : null,
                null, false));
        assertEquals(0, jdbc.queryForObject("SELECT consecutive_timeouts FROM game.match_players "
                + "WHERE match_id = ? AND user_id = ?", Integer.class, matchId, firstActor));
    }

    @Test
    void aFinishedClassicRoundAutomaticallyStartsTheNextRoundAfterItsDeadline() {
        GameIdentity host = player("Host");
        GameIdentity guest = player("Guest");
        UUID matchId = startMatch(host, guest).matchId();
        JsonMapper json = new JsonMapper();
        UnoSnapshot old = json.readValue(jdbc.queryForObject(
                "SELECT snapshot::text FROM game.matches WHERE id = ?", String.class, matchId), UnoSnapshot.class);
        List<List<Integer>> hands = new ArrayList<>();
        old.hands().forEach(hand -> hands.add(new ArrayList<>(hand)));
        List<Integer> pile = new ArrayList<>(old.drawPile());
        pile.addAll(hands.get(0));
        hands.set(0, List.of());
        UnoSnapshot roundOver = new UnoSnapshot(old.players(), hands, pile, old.discardPile(), old.scores(),
                old.dealerSeat(), old.currentSeat(), old.direction(), old.roundNumber(), old.version(),
                UnoState.Phase.ROUND_OVER, old.activeColor() == null ? UnoCard.Color.RED : old.activeColor(),
                null, null, null, 0, 25);
        UnoState.restore(roundOver);
        jdbc.update("UPDATE game.matches SET snapshot = CAST(? AS jsonb), deadline_at = ? WHERE id = ?",
                json.writeValueAsString(roundOver), Timestamp.from(Instant.now().minusSeconds(1)), matchId);

        var result = matches.resolveTimeout(matchId);

        assertEquals("AUTO_NEXT_ROUND_STARTED", result.event());
        assertEquals(2, matches.snapshot(matchId, host).view().roundNumber());
        assertEquals("PLAYING", matches.snapshot(matchId, host).status());
        assertNotNull(matches.snapshot(matchId, host).deadlineAt());
        assertEquals(0, jdbc.queryForObject("SELECT sum(consecutive_timeouts) FROM game.match_players "
                + "WHERE match_id = ?", Integer.class, matchId));
    }

    @Test
    void interruptedTeamMatchSchedulesVoiceCleanupAndGivesAllSeatsNeutralHistory() {
        GameIdentity host = player("Host");
        GameIdentity b = player("B");
        GameIdentity partner = player("Partner");
        GameIdentity d = player("D");
        RoomView room = rooms.create(host, "TEAM_2V2", 4);
        for (GameIdentity player : List.of(b, partner, d)) room = rooms.join(player, room.code());
        for (GameIdentity player : List.of(host, b, partner, d))
            room = rooms.ready(room.id(), player, true, room.version());
        UUID matchId = matches.start(room.id(), host, room.version()).matchId();
        var view = matches.snapshot(matchId, host).view();
        UUID actor = view.players().get(view.currentSeat()).userId();
        jdbc.update("UPDATE game.match_players SET consecutive_timeouts = 2 WHERE match_id = ? AND user_id = ?",
                matchId, actor);
        jdbc.update("UPDATE game.matches SET deadline_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), matchId);

        assertEquals("MATCH_INTERRUPTED", matches.resolveTimeout(matchId).event());

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM game.voice_cleanup WHERE match_id = ?",
                Integer.class, matchId));
        assertEquals("WAITING", rooms.get(room.id(), host).state());
        var history = application.getBean(MatchHistoryService.class);
        for (GameIdentity player : List.of(host, b, partner, d)) {
            var item = history.history(player, null, 20).items().get(0);
            assertEquals("TEAM_2V2", item.mode());
            assertEquals("INTERRUPTED", item.result());
            assertNull(item.winnerUserId());
            assertEquals(new MatchHistoryService.ModeStats(0, 0, 1), history.stats(player).team2v2());
        }
    }

    @Test
    void drawFourResponseGetsEightSecondsAndTimeoutAcceptsIt() {
        GameIdentity host = player("Host");
        GameIdentity guest = player("Guest");
        UUID matchId = startMatch(host, guest).matchId();
        JsonMapper json = new JsonMapper();
        UnoSnapshot old = json.readValue(jdbc.queryForObject(
                "SELECT snapshot::text FROM game.matches WHERE id = ?", String.class, matchId), UnoSnapshot.class);
        List<List<Integer>> hands = new ArrayList<>();
        old.hands().forEach(hand -> hands.add(new ArrayList<>(hand)));
        List<Integer> pile = new ArrayList<>(old.drawPile());
        int current = old.currentSeat();
        int plusFour = hands.get(current).stream()
                .filter(id -> UnoCard.of(id).kind() == UnoCard.Kind.WILD_DRAW_FOUR)
                .findFirst().orElse(-1);
        if (plusFour < 0) {
            for (int index = 0; index < pile.size(); index++) {
                if (UnoCard.of(pile.get(index)).kind() == UnoCard.Kind.WILD_DRAW_FOUR) {
                    plusFour = pile.get(index);
                    pile.set(index, hands.get(current).set(0, plusFour));
                    break;
                }
            }
        }
        if (plusFour < 0) {
            for (int seat = 0; seat < hands.size() && plusFour < 0; seat++) {
                if (seat == current) continue;
                for (int index = 0; index < hands.get(seat).size(); index++) {
                    if (UnoCard.of(hands.get(seat).get(index)).kind() == UnoCard.Kind.WILD_DRAW_FOUR) {
                        plusFour = hands.get(seat).get(index);
                        hands.get(seat).set(index, hands.get(current).set(0, plusFour));
                        break;
                    }
                }
            }
        }
        assertTrue(plusFour >= 0);
        UnoSnapshot prepared = new UnoSnapshot(old.players(), hands, pile, old.discardPile(), old.scores(),
                old.dealerSeat(), current, old.direction(), old.roundNumber(), old.version(),
                UnoState.Phase.TURN, old.activeColor() == null ? UnoCard.Color.RED : old.activeColor(),
                null, null, old.unoVulnerableSeat(), null, 0);
        UnoState.restore(prepared);
        jdbc.update("UPDATE game.matches SET snapshot = CAST(? AS jsonb) WHERE id = ?",
                json.writeValueAsString(prepared), matchId);
        GameIdentity actor = prepared.players().get(current).equals(host.userId()) ? host : guest;
        int cardId = plusFour;
        var played = matches.command(matchId, actor, new MatchCommandInput(1, UUID.randomUUID(), 1,
                MatchCommandInput.Type.PLAY, cardId, UnoCard.Color.BLUE, null, false));
        assertEquals(UnoState.Phase.DRAW_FOUR_RESPONSE, played.view().phase());
        long seconds = java.time.Duration.between(Instant.now(), played.deadlineAt()).toSeconds();
        assertTrue(seconds >= 6 && seconds <= 8);
        GameIdentity responder = actor == host ? guest : host;
        int handBefore = matches.state(matchId, responder).ownHand().size();
        jdbc.update("UPDATE game.matches SET deadline_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), matchId);
        MatchCommandInput late = new MatchCommandInput(1, UUID.randomUUID(), 2,
                MatchCommandInput.Type.CHALLENGE_DRAW_FOUR, null, null, null, false);
        assertEquals("TURN_EXPIRED", assertThrows(MatchFailure.class,
                () -> matches.command(matchId, responder, late)).code());
        var timedOut = matches.resolveTimeout(matchId);
        assertEquals("AUTO_DRAW_FOUR_ACCEPTED", timedOut.event());
        assertEquals(handBefore + 4, matches.state(matchId, responder).ownHand().size());
        assertNull(matches.resolveTimeout(matchId));
        assertEquals("TIMEOUT", jdbc.queryForObject(
                "SELECT source FROM game.match_commands WHERE event = 'AUTO_DRAW_FOUR_ACCEPTED'", String.class));
    }

    private MatchService.MatchStart startMatch(GameIdentity host, GameIdentity guest) {
        RoomView room = rooms.create(host, "CLASSIC", 2);
        room = rooms.join(guest, room.code());
        room = rooms.ready(room.id(), host, true, room.version());
        room = rooms.ready(room.id(), guest, true, room.version());
        return matches.start(room.id(), host, room.version());
    }

    private GameIdentity player(String nickname) {
        return new GameIdentity(UUID.randomUUID(), UUID.randomUUID(), nickname, "APP", Instant.now().plusSeconds(3600));
    }
}
