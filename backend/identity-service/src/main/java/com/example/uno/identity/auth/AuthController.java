package com.example.uno.identity.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
    private final AuthService auth;
    private final AuthRateLimiter limiter;
    private final AuthSettings settings;
    private final CookieCsrfTokenRepository csrf;

    public AuthController(AuthService auth, AuthRateLimiter limiter, AuthSettings settings, CookieCsrfTokenRepository csrf) {
        this.auth = auth;
        this.limiter = limiter;
        this.settings = settings;
        this.csrf = csrf;
    }

    @GetMapping("/api/auth/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/api/auth/register")
    ResponseEntity<Map<String, String>> register(@Valid @RequestBody Registration input) {
        limit("register", input.email());
        auth.register(input.email(), input.password(), input.nickname());
        return accepted();
    }

    @PostMapping("/api/auth/verification/request")
    ResponseEntity<Map<String, String>> requestVerification(@Valid @RequestBody EmailInput input) {
        limit("email", input.email());
        auth.requestVerification(input.email());
        return accepted();
    }

    @PostMapping("/api/auth/verify-email")
    ResponseEntity<Void> verify(@Valid @RequestBody TokenInput input) {
        auth.verifyEmail(input.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/auth/password/forgot")
    ResponseEntity<Map<String, String>> forgot(@Valid @RequestBody EmailInput input) {
        limit("email", input.email());
        auth.requestPasswordReset(input.email());
        return accepted();
    }

    @PostMapping("/api/auth/password/reset")
    ResponseEntity<Void> reset(@Valid @RequestBody ResetInput input, HttpServletRequest request, HttpServletResponse response) {
        auth.resetPassword(input.token(), input.password());
        if (!AuthHttpFilter.isNativeRequest(request)) clearCookies(request, response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/auth/login")
    Object login(@Valid @RequestBody LoginInput input, HttpServletRequest request, HttpServletResponse response) {
        limit("login", input.email());
        boolean nativeRequest = AuthHttpFilter.isNativeRequest(request);
        AuthService.LoginGrant grant = auth.login(input.email(), input.password(), nativeRequest ? "APP" : "WEB");
        if (nativeRequest) return appGrant(grant);
        setSessionCookie(response, grant.accessToken(), Duration.ofHours(12));
        csrf.saveToken(null, request, response);
        return Map.of("user", user(grant.identity()), "expiresAt", grant.identity().expiresAt());
    }

    @PostMapping("/api/auth/refresh")
    Object refresh(@Valid @RequestBody TokenInput input, HttpServletRequest request) {
        if (!AuthHttpFilter.isNativeRequest(request)) throw new AuthFailure(403, "APP_ONLY", "仅App使用刷新凭证");
        return appGrant(auth.refresh(input.token()));
    }

    @GetMapping("/api/users/me")
    Map<String, Object> me(@AuthenticationPrincipal SessionIdentity identity) {
        return user(identity);
    }

    @PostMapping("/api/auth/logout")
    ResponseEntity<Void> logout(@AuthenticationPrincipal SessionIdentity identity, HttpServletRequest request, HttpServletResponse response) {
        auth.logout(identity);
        if (!AuthHttpFilter.isNativeRequest(request)) clearCookies(request, response);
        return ResponseEntity.noContent().build();
    }

    private void clearCookies(HttpServletRequest request, HttpServletResponse response) {
        setSessionCookie(response, "", Duration.ZERO);
        csrf.saveToken(null, request, response);
    }

    private void setSessionCookie(HttpServletResponse response, String value, Duration lifetime) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(settings.sessionCookie(), value)
                .httpOnly(true).secure(settings.secureCookies()).sameSite("Strict").path("/").maxAge(lifetime).build().toString());
    }

    private void limit(String action, String email) {
        limiter.acquire(action, AuthService.normalizeEmail(email), settings.accountLimit());
    }

    private static ResponseEntity<Map<String, String>> accepted() {
        return ResponseEntity.accepted().body(Map.of("message", "请求已受理；若符合条件，将发送邮件，请仅使用最新凭证。"));
    }

    private static Map<String, Object> user(SessionIdentity identity) {
        return Map.of("id", identity.userId(), "email", identity.email(), "nickname", identity.nickname());
    }

    private static Map<String, Object> appGrant(AuthService.LoginGrant grant) {
        return Map.of("user", user(grant.identity()), "accessToken", grant.accessToken(), "tokenType", "Bearer",
                "expiresAt", grant.identity().expiresAt(), "refreshToken", grant.refreshToken(), "refreshExpiresAt", grant.refreshExpiresAt());
    }

    public record Registration(@NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 256) String password, @NotBlank @Size(max = 80) String nickname) {
        @Override public String toString() { return "Registration[redacted]"; }
    }
    public record LoginInput(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 256) String password) {
        @Override public String toString() { return "LoginInput[redacted]"; }
    }
    public record EmailInput(@NotBlank @Email @Size(max = 254) String email) { }
    public record TokenInput(@NotBlank @Size(min = 43, max = 43) String token) {
        @Override public String toString() { return "TokenInput[redacted]"; }
    }
    public record ResetInput(@NotBlank @Size(min = 43, max = 43) String token, @NotBlank @Size(max = 256) String password) {
        @Override public String toString() { return "ResetInput[redacted]"; }
    }
}
