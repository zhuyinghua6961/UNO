package com.example.uno.game.voice;

import com.example.uno.game.auth.GameIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/voice")
public class VoiceController {
    private final VoiceService voice;

    VoiceController(VoiceService voice) { this.voice = voice; }

    @PostMapping("/token")
    VoiceService.VoiceToken token(@AuthenticationPrincipal GameIdentity identity,
            @Valid @RequestBody TokenInput input) {
        return voice.issue(input.matchId(), identity);
    }

    public record TokenInput(@NotNull UUID matchId) { }
}
