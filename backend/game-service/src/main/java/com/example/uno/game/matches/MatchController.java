package com.example.uno.game.matches;

import com.example.uno.game.auth.GameIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class MatchController {
    private final MatchService matches;

    public MatchController(MatchService matches) { this.matches = matches; }

    @PostMapping("/rooms/{roomId}/start")
    MatchService.MatchStart start(@PathVariable UUID roomId, @AuthenticationPrincipal GameIdentity identity,
            @Valid @RequestBody StartInput input) {
        return matches.start(roomId, identity, input.expectedVersion());
    }

    @GetMapping("/matches/{matchId}/state")
    MatchService.MatchState state(@PathVariable UUID matchId, @AuthenticationPrincipal GameIdentity identity) {
        return matches.snapshot(matchId, identity);
    }

    @GetMapping("/rooms/{roomId}/match")
    ResponseEntity<MatchService.MatchStart> current(@PathVariable UUID roomId,
            @AuthenticationPrincipal GameIdentity identity) {
        MatchService.MatchStart match = matches.current(roomId, identity);
        return match == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(match);
    }

    @PostMapping("/matches/{matchId}/commands")
    MatchService.CommandResult command(@PathVariable UUID matchId, @AuthenticationPrincipal GameIdentity identity,
            @Valid @RequestBody MatchCommandInput input) {
        return matches.command(matchId, identity, input);
    }

    public record StartInput(@Min(1) long expectedVersion) { }
}
