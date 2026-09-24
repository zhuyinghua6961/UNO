package com.example.uno.game.voice;

public final class VoiceFailure extends RuntimeException {
    private final int status;
    private final String code;

    private VoiceFailure(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }
    public static VoiceFailure unavailable() { return new VoiceFailure(503, "VOICE_UNAVAILABLE", "队友语音暂不可用，请稍后重试"); }
    public static VoiceFailure notFound() { return new VoiceFailure(404, "VOICE_NOT_AVAILABLE", "当前对局没有可加入的队友语音"); }
    public static VoiceFailure rateLimited() { return new VoiceFailure(429, "VOICE_RATE_LIMITED", "语音加入过快，请稍后重试"); }
    public static VoiceFailure invalid() { return new VoiceFailure(400, "INVALID_VOICE_INPUT", "语音请求内容无效"); }
}
