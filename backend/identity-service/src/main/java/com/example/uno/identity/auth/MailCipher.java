package com.example.uno.identity.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class MailCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public MailCipher(AuthSettings settings) {
        key = settings.enabled() ? new SecretKeySpec(HexFormat.of().parseHex(settings.mailKey()), "AES") : null;
    }

    public String encrypt(UUID messageId, String recipient, String body) {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        byte[] encrypted = transform(Cipher.ENCRYPT_MODE, nonce, messageId, recipient, body.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
    }

    public String decrypt(UUID messageId, String recipient, String envelope) {
        ByteBuffer bytes = ByteBuffer.wrap(Base64.getDecoder().decode(envelope));
        if (bytes.remaining() < 28) throw new IllegalStateException("Invalid encrypted mail envelope");
        byte[] nonce = new byte[12];
        bytes.get(nonce);
        byte[] encrypted = new byte[bytes.remaining()];
        bytes.get(encrypted);
        return new String(transform(Cipher.DECRYPT_MODE, nonce, messageId, recipient, encrypted), StandardCharsets.UTF_8);
    }

    private byte[] transform(int mode, byte[] nonce, UUID messageId, String recipient, byte[] input) {
        if (key == null) throw AuthFailure.unavailable();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD((messageId + ":" + recipient).getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Encrypted mail operation failed");
        }
    }
}
