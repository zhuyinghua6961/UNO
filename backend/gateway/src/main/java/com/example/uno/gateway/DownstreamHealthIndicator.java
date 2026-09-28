package com.example.uno.gateway;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

@Component("downstream")
class DownstreamHealthIndicator implements ReactiveHealthIndicator {
    private final WebClient client;
    private final String identityReadiness;
    private final String gameReadiness;

    DownstreamHealthIndicator(@Value("${IDENTITY_URL:http://localhost:8081}") String identityUrl,
            @Value("${GAME_URL:http://localhost:8082}") String gameUrl) {
        HttpClient httpClient = HttpClient.create()
                .resolver(resolver -> resolver.cacheMinTimeToLive(Duration.ZERO)
                        .cacheMaxTimeToLive(Duration.ofSeconds(5))
                        .cacheNegativeTimeToLive(Duration.ofSeconds(1)))
                .responseTimeout(Duration.ofSeconds(2));
        this.client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient)).build();
        this.identityReadiness = identityUrl.replaceAll("/+$", "") + "/actuator/health/readiness";
        this.gameReadiness = gameUrl.replaceAll("/+$", "") + "/actuator/health/readiness";
    }

    @Override
    public Mono<Health> health() {
        return Mono.zip(ready(identityReadiness), ready(gameReadiness))
                .map(status -> status.getT1() && status.getT2() ? Health.up().build() : Health.down().build())
                .onErrorReturn(Health.down().build());
    }

    private Mono<Boolean> ready(String url) {
        return client.get().uri(url).exchangeToMono(response -> Mono.just(response.statusCode().is2xxSuccessful()))
                .timeout(Duration.ofSeconds(3));
    }
}
