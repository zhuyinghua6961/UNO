package com.example.uno.game.rooms;

public final class RoomFailure extends RuntimeException {
    private final int status;
    private final String code;

    private RoomFailure(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }
    public static RoomFailure invalid() { return new RoomFailure(400, "INVALID_ROOM_INPUT", "房间设置或房间码无效"); }
    public static RoomFailure notFound() { return new RoomFailure(404, "ROOM_NOT_FOUND", "房间不存在或邀请已失效"); }
    public static RoomFailure forbidden() { return new RoomFailure(403, "ROOM_FORBIDDEN", "只有房主或成员可以执行此操作"); }
    public static RoomFailure conflict(String message) { return new RoomFailure(409, "ROOM_CONFLICT", message); }
    public static RoomFailure full() { return new RoomFailure(409, "ROOM_FULL", "房间已满"); }
    public static RoomFailure rateLimited() { return new RoomFailure(429, "JOIN_RATE_LIMITED", "尝试加入过于频繁，请稍后再试"); }
}
