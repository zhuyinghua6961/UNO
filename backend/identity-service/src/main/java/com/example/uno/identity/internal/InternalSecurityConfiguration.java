package com.example.uno.identity.internal;

import com.example.uno.identity.auth.ApiErrors;
import com.example.uno.identity.auth.AuthFailure;
import com.example.uno.identity.auth.AuthRateLimiter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;

@Configuration
@EnableConfigurationProperties(InternalAuthSettings.class)
public class InternalSecurityConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain internalSecurity(HttpSecurity http, InternalAuthSettings settings, AuthRateLimiter limiter) throws Exception {
        return http.securityMatcher("/internal/**")
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, "/internal/auth/introspect").hasAuthority("SESSION_INTROSPECT")
                        .anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .addFilterBefore(new InternalServiceFilter(settings, limiter), AnonymousAuthenticationFilter.class)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> ApiErrors.send(request, response,
                                new AuthFailure(401, "SERVICE_UNAUTHORIZED", "服务凭证无效")))
                        .accessDeniedHandler((request, response, exception) -> ApiErrors.send(request, response,
                                new AuthFailure(403, "SERVICE_FORBIDDEN", "服务无权执行该操作"))))
                .build();
    }
}
