package com.example.uno.game.auth;

public class GameAuthFailure extends RuntimeException {
    private final int status;
    private final String code;

    private GameAuthFailure(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }
    public static GameAuthFailure unauthorized() { return new GameAuthFailure(401, "INVALID_CREDENTIALS", "登录已失效或凭证无效"); }
    public static GameAuthFailure forbidden() { return new GameAuthFailure(403, "REQUEST_NOT_ALLOWED", "请求来源、权限或CSRF验证失败"); }
    public static GameAuthFailure unavailable() { return new GameAuthFailure(503, "AUTH_UNAVAILABLE", "身份服务暂不可用，请稍后再试"); }
}
