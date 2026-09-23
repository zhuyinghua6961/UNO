package com.example.uno.identity;

import java.util.Map;
import com.example.uno.identity.auth.AuthSettings;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AuthStatusController {
    private final AuthSettings settings;

    AuthStatusController(AuthSettings settings) { this.settings = settings; }

    @GetMapping("/api/auth/status")
    Map<String, Object> status() {
        return Map.of("service", "identity-service", "stage", "backend-auth", "registrationAvailable", settings.enabled(),
                "loginAvailable", settings.enabled(), "emailVerificationRequired", true,
                "message", settings.enabled() ? "Backend authentication enabled; mail delivery is asynchronous"
                        : "Backend authentication implemented but disabled until explicitly configured");
    }
}
