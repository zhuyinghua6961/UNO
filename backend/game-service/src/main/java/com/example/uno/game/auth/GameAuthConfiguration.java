package com.example.uno.game.auth;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableConfigurationProperties(GameAuthSettings.class)
public class GameAuthConfiguration {
    @Bean(destroyMethod = "")
    HttpClient identityHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Bean
    HttpSessionVerifier sessionVerifier(HttpClient identityHttpClient, GameAuthSettings settings) {
        return new HttpSessionVerifier(identityHttpClient, new JsonMapper(), settings, Clock.systemUTC());
    }

    @Bean
    CookieCsrfTokenRepository csrfTokens(GameAuthSettings settings) {
        var repository = new CookieCsrfTokenRepository();
        repository.setCookieName(settings.csrfCookie());
        repository.setHeaderName("X-CSRF-TOKEN");
        repository.setCookieCustomizer(cookie -> cookie.path("/").httpOnly(true).secure(settings.secureCookies()).sameSite("Strict"));
        return repository;
    }
}
