package com.example.uno.identity;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AuthStatusController {
    @GetMapping("/api/auth/status")
    Map<String, Object> status() {
        return Map.of("service", "identity-service", "stage", "scaffold", "registrationAvailable", false,
                "loginAvailable", false, "message", "Account storage and authentication are not connected yet");
    }
}
