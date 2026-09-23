package com.example.uno.identity.auth;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class RequestBodies {
    private RequestBodies() { }

    public static HttpServletRequest bounded(HttpServletRequest request, int limit) throws IOException {
        if (request.getContentLengthLong() > limit) throw tooLarge();
        byte[] body = request.getInputStream().readNBytes(limit + 1);
        if (body.length > limit) throw tooLarge();
        return new BufferedRequest(request, body);
    }

    private static AuthFailure tooLarge() { return new AuthFailure(413, "BODY_TOO_LARGE", "请求内容过大"); }

    private static class BufferedRequest extends HttpServletRequestWrapper {
        private final ServletInputStream input;

        BufferedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            ByteArrayInputStream bytes = new ByteArrayInputStream(body);
            input = new ServletInputStream() {
                @Override public boolean isFinished() { return bytes.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous authentication body only"); }
                @Override public int read() { return bytes.read(); }
                @Override public int read(byte[] target, int offset, int length) { return bytes.read(target, offset, length); }
            };
        }

        @Override public ServletInputStream getInputStream() { return input; }
        @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)); }
    }
}
