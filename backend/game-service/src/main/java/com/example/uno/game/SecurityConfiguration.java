package com.example.uno.game;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.example.uno.game.auth.GameAuthFailure;
import com.example.uno.game.auth.GameAuthFilter;
import com.example.uno.game.auth.GameAuthSettings;
import com.example.uno.game.auth.HttpSessionVerifier;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;

@Configuration
class SecurityConfiguration {
    @Bean
    SecurityFilterChain security(HttpSecurity http, GameAuthSettings settings, HttpSessionVerifier verifier,
            CookieCsrfTokenRepository csrfTokens) throws Exception {
        return http.authorizeHttpRequests(requests -> requests
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/readiness",
                        "/actuator/health/liveness", "/api/system/bootstrap").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/system/session").hasAuthority("PLAYER")
                .requestMatchers("/api/rooms", "/api/rooms/**").hasAuthority("PLAYER")
                .requestMatchers(HttpMethod.GET, "/api/matches/history").hasAuthority("PLAYER")
                .requestMatchers(HttpMethod.GET, "/api/matches/*/state").hasAuthority("PLAYER")
                .requestMatchers(HttpMethod.POST, "/api/matches/*/commands").hasAuthority("PLAYER")
                .requestMatchers(HttpMethod.GET, "/ws/game").permitAll()
                .anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens).ignoringRequestMatchers(GameAuthFilter::isNativeRequest))
                .addFilterBefore(new GameAuthFilter(settings, verifier), CsrfFilter.class)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> GameAuthFilter.sendError(response, GameAuthFailure.unauthorized()))
                        .accessDeniedHandler((request, response, exception) -> GameAuthFilter.sendError(response, GameAuthFailure.forbidden())))
                .build();
    }

    @Bean
    UserDetailsService users() {
        return username -> { throw new UsernameNotFoundException("Only verified UNO sessions are supported"); };
    }
}
