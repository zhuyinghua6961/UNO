package com.example.uno.identity.auth;

public class AuthFailure extends RuntimeException {
    private final int status;
    private final String code;

    public AuthFailure(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }

    public static AuthFailure invalidCredentials() {
        return new AuthFailure(401, "INVALID_CREDENTIALS", "登录信息无效或账号不可用");
    }

    public static AuthFailure invalidToken() {
        return new AuthFailure(400, "INVALID_TOKEN", "凭证无效、已使用或已过期");
    }

    public static AuthFailure unavailable() {
        return new AuthFailure(503, "AUTH_UNAVAILABLE", "账号服务暂不可用");
    }
}
