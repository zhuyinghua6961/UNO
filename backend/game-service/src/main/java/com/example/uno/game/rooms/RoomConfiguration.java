package com.example.uno.game.rooms;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
class RoomConfiguration {
    @Bean Clock roomClock() { return Clock.systemUTC(); }
    @Bean RoomJoinLimiter roomJoinLimiter(Clock roomClock) { return new RoomJoinLimiter(roomClock); }
}
