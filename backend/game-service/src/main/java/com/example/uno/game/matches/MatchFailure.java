package com.example.uno.game.matches;

public final class MatchFailure extends RuntimeException {
    private final int status;
    private final String code;

    private MatchFailure(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }
    public static MatchFailure notFound() { return new MatchFailure(404, "MATCH_NOT_FOUND", "对局不存在或无权查看"); }
    public static MatchFailure forbidden() { return new MatchFailure(403, "MATCH_FORBIDDEN", "只有房主能启动对局"); }
    public static MatchFailure conflict() { return new MatchFailure(409, "MATCH_CONFLICT", "房间状态已变化，请刷新后重试"); }
    public static MatchFailure socketOwned() { return new MatchFailure(409, "MATCH_SOCKET_OWNED", "当前对局由实时连接操作，请从该连接出牌"); }
    public static MatchFailure turnExpired() { return new MatchFailure(409, "TURN_EXPIRED", "操作窗口已结束，请等待服务端裁决"); }
    public static MatchFailure invalid() { return new MatchFailure(400, "INVALID_MATCH_INPUT", "对局指令内容无效"); }
    public static MatchFailure rule(String code) { return new MatchFailure(422, code, "对局规则拒绝此动作"); }
}
