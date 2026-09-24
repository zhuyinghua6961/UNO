package com.example.uno.game.matches;

import com.example.uno.game.auth.GameIdentity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MatchHistoryController {
    private final MatchHistoryService history;

    public MatchHistoryController(MatchHistoryService history) { this.history = history; }

    @GetMapping("/api/matches/history")
    MatchHistoryService.Page history(@AuthenticationPrincipal GameIdentity identity,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        return history.history(identity, cursor, limit);
    }

    @GetMapping("/api/matches/stats")
    MatchHistoryService.Stats stats(@AuthenticationPrincipal GameIdentity identity) {
        return history.stats(identity);
    }
}
