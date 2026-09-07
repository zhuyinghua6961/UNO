package com.example.uno.core;

public record ChatMessage(String content) {
    public ChatMessage {
        if (content == null || content.isBlank() || content.codePointCount(0, content.length()) > 500) {
            throw new IllegalArgumentException("Message must contain 1 to 500 code points");
        }
        if (content.codePoints().anyMatch(value -> Character.isISOControl(value) && value != '\n')) {
            throw new IllegalArgumentException("Unsupported control character");
        }
    }
}
