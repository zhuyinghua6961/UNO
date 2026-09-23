package com.example.uno.game.rooms;

import com.example.uno.game.auth.GameIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {
    private final RoomService rooms;

    public RoomController(RoomService rooms) { this.rooms = rooms; }

    @GetMapping("/current")
    ResponseEntity<RoomView> current(@AuthenticationPrincipal GameIdentity identity) {
        RoomView room = rooms.current(identity);
        return room == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(room);
    }

    @PostMapping
    ResponseEntity<RoomView> create(@AuthenticationPrincipal GameIdentity identity, @Valid @RequestBody CreateInput input) {
        RoomView room = rooms.create(identity, input.mode(), input.maxPlayers());
        return ResponseEntity.created(URI.create("/api/rooms/" + room.id())).body(room);
    }

    @PostMapping("/join")
    RoomView join(@AuthenticationPrincipal GameIdentity identity, @Valid @RequestBody JoinInput input) {
        return rooms.join(identity, input.code());
    }

    @GetMapping("/{id}")
    RoomView get(@PathVariable UUID id, @AuthenticationPrincipal GameIdentity identity) {
        return rooms.get(id, identity);
    }

    @PostMapping("/{id}/leave")
    ResponseEntity<Void> leave(@PathVariable UUID id, @AuthenticationPrincipal GameIdentity identity) {
        rooms.leave(id, identity);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/ready")
    RoomView ready(@PathVariable UUID id, @AuthenticationPrincipal GameIdentity identity, @Valid @RequestBody ReadyInput input) {
        return rooms.ready(id, identity, input.ready(), input.expectedVersion());
    }

    @PostMapping("/{id}/team")
    RoomView team(@PathVariable UUID id, @AuthenticationPrincipal GameIdentity identity, @Valid @RequestBody TeamInput input) {
        return rooms.selectTeam(id, identity, input.team(), input.expectedVersion());
    }

    @PostMapping("/{id}/settings")
    RoomView settings(@PathVariable UUID id, @AuthenticationPrincipal GameIdentity identity, @Valid @RequestBody SettingsInput input) {
        return rooms.changeMaxPlayers(id, identity, input.maxPlayers(), input.expectedVersion());
    }

    public record CreateInput(@NotBlank @Pattern(regexp = "CLASSIC|TEAM_2V2") String mode,
            @Min(2) @Max(6) int maxPlayers) { }
    public record JoinInput(@NotBlank String code) { }
    public record ReadyInput(@NotNull Boolean ready, @Min(1) long expectedVersion) { }
    public record TeamInput(@NotBlank @Pattern(regexp = "A|B") String team, @Min(1) long expectedVersion) { }
    public record SettingsInput(@Min(2) @Max(6) int maxPlayers, @Min(1) long expectedVersion) { }
}
