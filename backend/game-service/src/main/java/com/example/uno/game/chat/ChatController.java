package com.example.uno.game.chat;

import com.example.uno.game.auth.GameIdentity;
import com.example.uno.game.realtime.ChatWebSocketHandler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rooms/{roomId}/messages")
public class ChatController {
    private final ChatService chat;
    private final ChatWebSocketHandler socket;

    public ChatController(ChatService chat, ChatWebSocketHandler socket) {
        this.chat = chat;
        this.socket = socket;
    }

    @PostMapping
    ChatService.ChatItem send(@PathVariable UUID roomId, @AuthenticationPrincipal GameIdentity identity,
            @Valid @RequestBody SendInput input) {
        ChatService.ChatItem saved = chat.send(roomId, identity, input.clientMessageId(), input.content(),
                input.channel() == null ? "ROOM" : input.channel());
        socket.publish(saved);
        return saved;
    }

    @GetMapping
    ChatService.ChatPage history(@PathVariable UUID roomId, @AuthenticationPrincipal GameIdentity identity,
            @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean latest,
            @RequestParam(defaultValue = "ROOM") String channel) {
        return chat.history(roomId, identity, after, limit, latest, channel);
    }

    public record SendInput(@NotNull UUID clientMessageId, @NotNull String content, String channel) { }
}
