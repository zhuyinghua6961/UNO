package com.example.uno.identity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import com.example.uno.identity.auth.ApiErrors;
import com.example.uno.identity.auth.AuthFailure;
import com.example.uno.identity.auth.AuthHttpFilter;
import com.example.uno.identity.auth.AuthRateLimiter;
import com.example.uno.identity.auth.AuthService;
import com.example.uno.identity.auth.AuthSettings;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;

@Configuration
class SecurityConfiguration {
    @Bean
    CookieCsrfTokenRepository csrfTokens(AuthSettings settings) {
        var repository = new CookieCsrfTokenRepository();
        repository.setCookieName(settings.csrfCookie());
        repository.setHeaderName("X-CSRF-TOKEN");
        repository.setCookieCustomizer(cookie -> cookie.path("/").httpOnly(true).secure(settings.secureCookies()).sameSite("Strict"));
        return repository;
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, AuthSettings settings, AuthService auth,
            AuthRateLimiter limiter, CookieCsrfTokenRepository csrfTokens) throws Exception {
        return http.authorizeHttpRequests(requests -> requests
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/readiness",
                        "/actuator/health/liveness", "/api/auth/status", "/api/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/auth/refresh",
                        "/api/auth/verification/request", "/api/auth/verify-email", "/api/auth/password/forgot", "/api/auth/password/reset").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/users/me").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/auth/logout").authenticated()
                .anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens).ignoringRequestMatchers(AuthHttpFilter::isNativeRequest))
                .addFilterBefore(new AuthHttpFilter(settings, auth, limiter), CsrfFilter.class)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> ApiErrors.send(request, response, AuthFailure.invalidCredentials()))
                        .accessDeniedHandler((request, response, exception) -> ApiErrors.send(request, response,
                                new AuthFailure(403, "REQUEST_NOT_ALLOWED", "权限或CSRF验证失败"))))
                .build();
    }

    @Bean
    UserDetailsService users() {
        return username -> { throw new UsernameNotFoundException("Only explicit UNO session authentication is supported"); };
    }
}
