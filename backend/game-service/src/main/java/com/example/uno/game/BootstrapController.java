package com.example.uno.game;

import java.util.List;
import java.util.Map;
import com.example.uno.game.auth.GameAuthSettings;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class BootstrapController {
    private final GameAuthSettings settings;

    BootstrapController(GameAuthSettings settings) { this.settings = settings; }

    @GetMapping("/api/system/bootstrap")
    Map<String, Object> bootstrap() {
        return Map.of("service", "game-service", "stage", "scaffold", "protocolVersion", 1,
                "plannedModes", List.of("CLASSIC", "TEAM_2V2"),
                "features", Map.of("authentication", settings.enabled(), "rooms", settings.enabled(), "gameplay", settings.enabled(), "roomText", settings.enabled(),
                        "teamText", false, "teamVoice", false));
    }
}
