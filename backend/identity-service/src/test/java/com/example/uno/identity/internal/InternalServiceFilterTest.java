package com.example.uno.identity.internal;

import com.example.uno.identity.auth.AuthRateLimiter;
import com.example.uno.identity.auth.AuthFailure;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalServiceFilterTest {
    private static final String KEY = "d".repeat(64);

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void secureTransportAndDedicatedCredentialGrantOnlyIntrospection() throws Exception {
        var limiter = mock(AuthRateLimiter.class);
        var request = request();
        request.setSecure(true);
        var reached = new AtomicBoolean();
        new InternalServiceFilter(settings(false), limiter).doFilter(request, new MockHttpServletResponse(), (incoming, outgoing) -> {
            reached.set(true);
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertEquals("game-service", authentication.getName());
            assertEquals("SESSION_INTROSPECT", authentication.getAuthorities().iterator().next().getAuthority());
            assertNull(authentication.getCredentials());
        });
        assertTrue(reached.get());
        verifyNoInteractions(limiter);
    }

    @Test
    void forwardedHttpsHeaderCannotReplaceActualTls() throws Exception {
        var request = request();
        request.addHeader("X-Forwarded-Proto", "https");
        var response = new MockHttpServletResponse();
        new InternalServiceFilter(settings(false), mock(AuthRateLimiter.class)).doFilter(request, response,
                (incoming, outgoing) -> fail("Plain HTTP must not reach introspection"));
        assertEquals(403, response.getStatus());
        assertFalse(response.getContentAsString().contains(KEY));
    }

    @Test
    void missingInvalidAndRepeatedCredentialsCannotAuthenticate() throws Exception {
        for (int variant = 0; variant < 3; variant++) {
            var request = request();
            request.setSecure(true);
            if (variant == 0) request.removeHeader("Authorization");
            if (variant == 1) { request.removeHeader("Authorization"); request.addHeader("Authorization", "Bearer " + KEY); }
            if (variant == 2) request.addHeader("Authorization", "Basic invalid");
            var limiter = mock(AuthRateLimiter.class);
            var response = new MockHttpServletResponse();
            new InternalServiceFilter(settings(false), limiter).doFilter(request, response, (incoming, outgoing) -> fail("Invalid service auth"));
            assertEquals(401, response.getStatus());
            verify(limiter).acquire("internal-invalid", "127.0.0.1", 120);
        }
    }

    @Test
    void invalidCredentialBudgetDoesNotBlockTheRealGameService() throws Exception {
        var limiter = mock(AuthRateLimiter.class);
        doThrow(new AuthFailure(429, "RATE_LIMITED", "请求过于频繁，请稍后再试"))
                .when(limiter).acquire("internal-invalid", "192.0.2.10", 120);
        var invalid = request();
        invalid.setSecure(true);
        invalid.setRemoteAddr("192.0.2.10");
        invalid.removeHeader("Authorization");
        var denied = new MockHttpServletResponse();
        var filter = new InternalServiceFilter(settings(false), limiter);
        filter.doFilter(invalid, denied, (incoming, outgoing) -> fail("Invalid service auth"));
        assertEquals(429, denied.getStatus());

        var valid = request();
        valid.setSecure(true);
        var reached = new AtomicBoolean();
        filter.doFilter(valid, new MockHttpServletResponse(), (incoming, outgoing) -> reached.set(true));
        assertTrue(reached.get());
        verify(limiter).acquire("internal-invalid", "192.0.2.10", 120);
        verifyNoMoreInteractions(limiter);
    }

    @Test
    void browsersCannotUseServiceCredentialsEvenOnTls() throws Exception {
        for (String header : new String[]{"Cookie", "Origin", "Sec-Fetch-Site"}) {
            var request = request();
            request.setSecure(true);
            request.addHeader(header, "untrusted");
            var response = new MockHttpServletResponse();
            new InternalServiceFilter(settings(false), mock(AuthRateLimiter.class)).doFilter(request, response,
                    (incoming, outgoing) -> fail("Browser must not enter service-only chain"));
            assertEquals(403, response.getStatus());
        }
    }

    @Test
    void configurationNeverPrintsTheServiceSecret() {
        assertFalse(settings(false).toString().contains(KEY));
        assertThrows(IllegalArgumentException.class, () -> new InternalAuthSettings(true, "short", false, 10000));
    }

    private InternalAuthSettings settings(boolean allowHttp) { return new InternalAuthSettings(true, KEY, allowHttp, 120); }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", "/internal/auth/introspect");
        request.addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString(("game-service:" + KEY).getBytes(StandardCharsets.UTF_8)));
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
