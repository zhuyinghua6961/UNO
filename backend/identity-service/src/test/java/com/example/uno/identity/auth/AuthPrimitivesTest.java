package com.example.uno.identity.auth;

import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthPrimitivesTest {
    @Test
    void opaqueTokensAreIndependentAndDigestOnly() {
        var values = new HashSet<String>();
        for (int index = 0; index < 100; index++) {
            String token = Secrets.token();
            assertTrue(Secrets.validToken(token));
            assertTrue(values.add(token));
            assertEquals(64, Secrets.digest(token).length());
            assertNotEquals(token, Secrets.digest(token));
        }
        assertFalse(Secrets.validToken("short"));
        assertFalse(Secrets.validToken(null));
    }

    @Test
    void encryptedMailUsesFreshNonceAndAuthenticatesRecipient() {
        var cipher = new MailCipher(settings(false, "http://localhost:5179", "a".repeat(64)));
        UUID messageId = UUID.randomUUID();
        String first = cipher.encrypt(messageId, "player@example.test", "a secret token");
        String second = cipher.encrypt(messageId, "player@example.test", "a secret token");
        assertNotEquals(first, second);
        assertEquals("a secret token", cipher.decrypt(messageId, "player@example.test", first));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(messageId, "attacker@example.test", first));
        byte[] tampered = Base64.getDecoder().decode(first);
        tampered[tampered.length - 1] ^= 1;
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(messageId, "player@example.test", Base64.getEncoder().encodeToString(tampered)));
    }

    @Test
    void unsafeActivationConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> settings(true, "http://localhost:5179", "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> settings(false, "https://example.test", "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> settings(true, "https://example.test/", "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> settings(true, "https://example.test", "short"));
        assertThrows(IllegalArgumentException.class, () -> settings(true, "null", "a".repeat(64)));
        assertEquals("__Host-UNO-SESSION", settings(true, "https://example.test", "a".repeat(64)).sessionCookie());
        assertFalse(settings(true, "https://example.test", "a".repeat(64)).toString().contains("a".repeat(64)));
    }

    @Test
    void passwordHashIsSaltedVersionedAndChecksTheEntirePassword() {
        var encoder = new AuthConfiguration().passwordEncoder();
        String password = "a sufficiently long password 密码";
        String encoded = encoder.encode(password);
        assertTrue(encoded.startsWith("{pbkdf2-sha256}"));
        assertTrue(encoder.matches(password, encoded));
        assertFalse(encoder.matches(password + "x", encoded));
        assertNotEquals(encoded, encoder.encode(password));
    }

    private AuthSettings settings(boolean secure, String origin, String key) {
        return new AuthSettings(true, secure, List.of(origin), key, "uno@localhost", false, 120, 10);
    }
}
