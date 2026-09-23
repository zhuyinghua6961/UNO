package com.example.uno.identity.internal;

import com.example.uno.identity.auth.AuthFailure;
import com.example.uno.identity.auth.AuthService;
import com.example.uno.identity.auth.AuthSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IntrospectionController {
    private final AuthService auth;
    private final AuthSettings settings;

    public IntrospectionController(AuthService auth, AuthSettings settings) {
        this.auth = auth;
        this.settings = settings;
    }

    @PostMapping("/internal/auth/introspect")
    Map<String, Object> introspect(@Valid @RequestBody IntrospectionInput input) {
        if (!settings.enabled()) throw AuthFailure.unavailable();
        return auth.authenticate(input.token(), input.clientType())
                .map(identity -> Map.<String, Object>of("active", true, "userId", identity.userId(),
                        "sessionId", identity.sessionId(), "clientType", identity.clientType(), "expiresAt", identity.expiresAt(),
                        "nickname", identity.nickname(),
                        "audience", "game-service", "protocolVersion", 1))
                .orElseGet(() -> Map.of("active", false));
    }

    public record IntrospectionInput(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token,
            @NotNull @Pattern(regexp = "WEB|APP") String clientType) {
        @Override public String toString() { return "IntrospectionInput[redacted]"; }
    }
}
