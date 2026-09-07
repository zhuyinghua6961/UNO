package com.example.uno.identity.persistence;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcAccountStore {
    private final JdbcClient jdbc;

    public JdbcAccountStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Account create(String email, String passwordHash, String nickname) {
        UUID accountId = UUID.randomUUID();
        jdbc.sql("INSERT INTO accounts (id, email, password_hash, nickname) VALUES (?, ?, ?, ?)")
                .params(accountId, normalizeEmail(email), passwordHash, nickname.strip()).update();
        return findById(accountId).orElseThrow();
    }

    public Optional<Account> findById(UUID accountId) {
        return jdbc.sql("SELECT id, email, nickname, status, created_at FROM accounts WHERE id = ?")
                .param(accountId)
                .query((row, rowNumber) -> new Account(row.getObject("id", UUID.class),
                        row.getString("email"), row.getString("nickname"), row.getString("status"),
                        row.getTimestamp("created_at").toInstant()))
                .optional();
    }

    private String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
