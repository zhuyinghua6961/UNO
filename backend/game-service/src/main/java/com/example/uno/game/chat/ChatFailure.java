package com.example.uno.game.chat;

public final class ChatFailure extends RuntimeException {
    private final int status;
    private final String code;

    private ChatFailure(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }

    public static ChatFailure invalid() { return new ChatFailure(400, "INVALID_CHAT_INPUT", "消息内容无效"); }
    public static ChatFailure notFound() { return new ChatFailure(404, "CHAT_ROOM_NOT_FOUND", "房间不存在或你不是成员"); }
    public static ChatFailure conflict() { return new ChatFailure(409, "CHAT_MESSAGE_CONFLICT", "消息 ID 已用于其他内容"); }
    public static ChatFailure rateLimited() { return new ChatFailure(429, "CHAT_RATE_LIMITED", "发送过快，请稍后重试"); }
    public static ChatFailure muted() { return new ChatFailure(403, "CHAT_MUTED", "当前账号暂不能发送文字消息"); }
    public static ChatFailure reportNotFound() { return new ChatFailure(404, "CHAT_MESSAGE_NOT_FOUND", "消息不存在或不可见"); }
}
