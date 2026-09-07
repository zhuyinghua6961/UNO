package com.example.uno.game;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class BootstrapController {
    @GetMapping("/api/system/bootstrap")
    Map<String, Object> bootstrap() {
        return Map.of("service", "game-service", "stage", "scaffold", "protocolVersion", 1,
                "plannedModes", List.of("CLASSIC", "TEAM_2V2"),
                "features", Map.of("authentication", false, "gameplay", false, "roomText", false,
                        "teamText", false, "teamVoice", false));
    }
}
