package com.example.uno.identity.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class MailOutbox {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final MailCipher cipher;
    private final JavaMailSender sender;
    private final AuthSettings settings;
    private final TransactionTemplate transaction;

    public MailOutbox(JdbcTemplate jdbc, Clock clock, MailCipher cipher, JavaMailSender sender,
            AuthSettings settings, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.cipher = cipher;
        this.sender = sender;
        this.settings = settings;
        transaction = new TransactionTemplate(manager);
    }

    public void enqueue(String recipient, String subject, String body, Instant expiresAt) {
        UUID messageId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mail_outbox (id, recipient, subject, encrypted_body, created_at, next_attempt_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, messageId, recipient, subject, cipher.encrypt(messageId, recipient, body),
                Timestamp.from(clock.instant()), Timestamp.from(clock.instant()), Timestamp.from(expiresAt));
    }

    @Scheduled(initialDelayString = "${uno.auth.mail-poll-ms:5000}", fixedDelayString = "${uno.auth.mail-poll-ms:5000}")
    public void poll() {
        if (settings.enabled() && settings.mailDispatchEnabled()) deliverBatch();
    }

    public void deliverBatch() {
        if (!settings.enabled()) return;
        for (int index = 0; index < 10; index++) {
            Boolean found = transaction.execute(status -> {
                var messages = jdbc.query("""
                        SELECT id, recipient, subject, encrypted_body, attempts FROM mail_outbox
                        WHERE sent_at IS NULL AND next_attempt_at <= ? AND expires_at > ? AND attempts < 5
                        ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                        """, (row, rowNumber) -> new PendingMail(row.getObject("id", UUID.class), row.getString("recipient"),
                        row.getString("subject"), row.getString("encrypted_body"), row.getInt("attempts")),
                        Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
                if (messages.isEmpty()) return false;
                PendingMail pending = messages.get(0);
                try {
                    SimpleMailMessage message = new SimpleMailMessage();
                    message.setFrom(settings.mailFrom());
                    message.setTo(pending.recipient());
                    message.setSubject(pending.subject());
                    message.setText(cipher.decrypt(pending.id(), pending.recipient(), pending.envelope()));
                    sender.send(message);
                    jdbc.update("UPDATE mail_outbox SET sent_at = ?, encrypted_body = NULL, attempts = attempts + 1, last_error = NULL WHERE id = ?",
                            Timestamp.from(clock.instant()), pending.id());
                } catch (org.springframework.mail.MailException | IllegalStateException exception) {
                    jdbc.update("UPDATE mail_outbox SET attempts = attempts + 1, last_error = 'DELIVERY_FAILED', next_attempt_at = ? WHERE id = ?",
                            Timestamp.from(clock.instant().plus(Duration.ofMinutes(1L << pending.attempts()))), pending.id());
                }
                return true;
            });
            if (!Boolean.TRUE.equals(found)) break;
        }
    }

    private record PendingMail(UUID id, String recipient, String subject, String envelope, int attempts) { }
}
