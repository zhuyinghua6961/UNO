package com.example.uno.game.auth;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GameSessionController {
    @GetMapping("/api/system/session")
    GameIdentity session(@AuthenticationPrincipal GameIdentity identity) { return identity; }
}
