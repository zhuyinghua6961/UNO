package com.example.uno.identity.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final MailOutbox mail;
    private final TransactionTemplate transaction;
    private final String dummyHash;

    public AuthService(JdbcTemplate jdbc, PasswordEncoder passwords, Clock clock, MailOutbox mail,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.clock = clock;
        this.mail = mail;
        transaction = new TransactionTemplate(manager);
        dummyHash = passwords.encode(Secrets.token());
    }

    public void register(String email, String password, String nickname) {
        String normalized = normalizeEmail(email);
        validatePassword(password);
        String chosenNickname = validatedNickname(nickname);
        String encoded = passwords.encode(password);
        transaction.executeWithoutResult(status -> {
            Instant now = clock.instant();
            jdbc.update("""
                    INSERT INTO accounts (id, email, password_hash, nickname, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (email) DO NOTHING
                    """, UUID.randomUUID(), normalized, encoded, chosenNickname, timestamp(now), timestamp(now));
            AccountRow account = accountByEmail(normalized).orElseThrow();
            if (!account.status().equals("PENDING")) return;
            jdbc.update("UPDATE accounts SET password_hash = ?, nickname = ?, updated_at = GREATEST(created_at, ?) WHERE id = ?",
                    encoded, chosenNickname, timestamp(now), account.id());
            issueAccountToken(account, "VERIFY_EMAIL", Duration.ofHours(24));
        });
    }

    public void requestVerification(String email) {
        String normalized = normalizeEmail(email);
        transaction.executeWithoutResult(status -> accountByEmail(normalized).filter(account -> account.status().equals("PENDING"))
                .ifPresent(account -> issueAccountToken(account, "VERIFY_EMAIL", Duration.ofHours(24))));
    }

    public void requestPasswordReset(String email) {
        String normalized = normalizeEmail(email);
        transaction.executeWithoutResult(status -> accountByEmail(normalized).filter(account -> account.status().equals("ACTIVE"))
                .ifPresent(account -> issueAccountToken(account, "RESET_PASSWORD", Duration.ofMinutes(30))));
    }

    public void verifyEmail(String token) {
        transaction.executeWithoutResult(status -> {
            AccountRow account = consumeAccountToken(token, "VERIFY_EMAIL", "PENDING");
            Instant now = clock.instant();
            jdbc.update("UPDATE accounts SET status = 'ACTIVE', email_verified_at = ?, updated_at = GREATEST(created_at, ?) WHERE id = ?",
                    timestamp(now), timestamp(now), account.id());
        });
    }

    public void resetPassword(String token, String password) {
        validatePassword(password);
        String encoded = passwords.encode(password);
        transaction.executeWithoutResult(status -> {
            AccountRow account = consumeAccountToken(token, "RESET_PASSWORD", "ACTIVE");
            Instant now = clock.instant();
            jdbc.update("UPDATE accounts SET password_hash = ?, updated_at = GREATEST(created_at, ?) WHERE id = ?",
                    encoded, timestamp(now), account.id());
            revokeAll(account.id());
            mail.enqueue(account.email(), "UNO 密码已修改", "您的密码已修改，所有旧会话已退出。如非本人操作，请联系管理员。",
                    now.plus(Duration.ofDays(1)));
        });
    }

    public LoginGrant login(String email, String password, String clientType) {
        String normalized = normalizeEmail(email);
        validatePassword(password);
        return transaction.execute(status -> {
            Optional<AccountRow> found = accountByEmail(normalized);
            String encoded = found.map(AccountRow::hash).orElse(dummyHash);
            boolean matches;
            try {
                matches = passwords.matches(password, encoded);
            } catch (IllegalArgumentException exception) {
                passwords.matches(password, dummyHash);
                matches = false;
            }
            if (!matches || found.isEmpty() || !found.get().status().equals("ACTIVE")) throw AuthFailure.invalidCredentials();
            AccountRow account = found.get();
            if (passwords.upgradeEncoding(encoded)) jdbc.update("UPDATE accounts SET password_hash = ? WHERE id = ?",
                    passwords.encode(password), account.id());
            Instant now = clock.instant();
            Instant absoluteExpiry = now.plus(clientType.equals("WEB") ? Duration.ofHours(12) : Duration.ofDays(30));
            Instant expiry = clientType.equals("WEB") ? absoluteExpiry : now.plus(Duration.ofMinutes(15));
            UUID sessionId = UUID.randomUUID();
            String access = Secrets.token();
            jdbc.update("""
                    INSERT INTO sessions (id, account_id, token_digest, client_type, created_at, expires_at, absolute_expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, sessionId, account.id(), Secrets.digest(access), clientType, timestamp(now), timestamp(expiry), timestamp(absoluteExpiry));
            String refresh = clientType.equals("APP") ? issueRefresh(sessionId, absoluteExpiry) : null;
            return new LoginGrant(new SessionIdentity(account.id(), sessionId, account.email(), account.nickname(), clientType, expiry),
                    access, refresh, refresh == null ? null : absoluteExpiry);
        });
    }

    public Optional<SessionIdentity> authenticate(String token, String clientType) {
        if (!Secrets.validToken(token)) return Optional.empty();
        return jdbc.query("""
                SELECT account.id AS user_id, session.id AS session_id, account.email, account.nickname,
                    session.client_type, session.expires_at FROM sessions session JOIN accounts account ON account.id = session.account_id
                WHERE session.token_digest = ? AND session.client_type = ? AND session.revoked_at IS NULL
                    AND session.expires_at > ? AND session.absolute_expires_at > ? AND account.status = 'ACTIVE'
                """, (row, rowNumber) -> new SessionIdentity(row.getObject("user_id", UUID.class), row.getObject("session_id", UUID.class),
                row.getString("email"), row.getString("nickname"), row.getString("client_type"), row.getTimestamp("expires_at").toInstant()),
                Secrets.digest(token), clientType, timestamp(clock.instant()), timestamp(clock.instant())).stream().findFirst();
    }

    public LoginGrant refresh(String token) {
        if (!Secrets.validToken(token)) throw AuthFailure.invalidCredentials();
        LoginGrant grant = transaction.execute(status -> {
            var owners = jdbc.query("SELECT session.account_id FROM refresh_tokens refresh JOIN sessions session ON session.id = refresh.session_id WHERE refresh.token_digest = ?",
                    (row, rowNumber) -> row.getObject(1, UUID.class), Secrets.digest(token));
            if (owners.isEmpty()) return null;
            AccountRow account = lockAccount(owners.get(0));
            var tokens = jdbc.query("""
                    SELECT refresh.id, refresh.session_id, refresh.expires_at, refresh.consumed_at,
                        session.revoked_at, session.absolute_expires_at FROM refresh_tokens refresh
                    JOIN sessions session ON session.id = refresh.session_id
                    WHERE refresh.token_digest = ? AND session.client_type = 'APP' FOR UPDATE OF refresh, session
                    """, (row, rowNumber) -> new RefreshRow(row.getObject("id", UUID.class), row.getObject("session_id", UUID.class),
                    row.getTimestamp("expires_at").toInstant(), row.getTimestamp("consumed_at") != null,
                    row.getTimestamp("revoked_at") != null, row.getTimestamp("absolute_expires_at").toInstant()), Secrets.digest(token));
            if (tokens.isEmpty()) return null;
            RefreshRow previous = tokens.get(0);
            if (previous.consumed()) {
                revokeSession(previous.sessionId());
                return null;
            }
            Instant now = clock.instant();
            if (previous.revoked() || !account.status().equals("ACTIVE")
                    || !previous.expiresAt().isAfter(now) || !previous.absoluteExpiry().isAfter(now)) return null;
            jdbc.update("UPDATE refresh_tokens SET consumed_at = ? WHERE id = ?", timestamp(now), previous.id());
            String access = Secrets.token();
            Instant expiry = now.plus(Duration.ofMinutes(15));
            if (expiry.isAfter(previous.absoluteExpiry())) expiry = previous.absoluteExpiry();
            jdbc.update("UPDATE sessions SET token_digest = ?, expires_at = ? WHERE id = ?",
                    Secrets.digest(access), timestamp(expiry), previous.sessionId());
            String refresh = issueRefresh(previous.sessionId(), previous.absoluteExpiry());
            return new LoginGrant(new SessionIdentity(account.id(), previous.sessionId(), account.email(), account.nickname(), "APP", expiry),
                    access, refresh, previous.absoluteExpiry());
        });
        if (grant == null) throw AuthFailure.invalidCredentials();
        return grant;
    }

    public void logout(SessionIdentity identity) {
        transaction.executeWithoutResult(status -> {
            lockAccount(identity.userId());
            revokeSession(identity.sessionId());
        });
    }

    public String updateNickname(SessionIdentity identity, String nickname) {
        String chosen = validatedNickname(nickname);
        transaction.executeWithoutResult(status -> {
            AccountRow account = lockAccount(identity.userId());
            if (!account.status().equals("ACTIVE")) throw AuthFailure.invalidCredentials();
            jdbc.update("UPDATE accounts SET nickname = ?, updated_at = GREATEST(created_at, ?) WHERE id = ?",
                    chosen, timestamp(clock.instant()), identity.userId());
        });
        return chosen;
    }

    public void disableAccount(UUID userId) {
        transaction.executeWithoutResult(status -> {
            lockAccount(userId);
            jdbc.update("UPDATE accounts SET status = 'DISABLED', updated_at = GREATEST(created_at, ?) WHERE id = ?", timestamp(clock.instant()), userId);
            revokeAll(userId);
            jdbc.update("UPDATE account_tokens SET consumed_at = GREATEST(created_at, ?) WHERE account_id = ? AND consumed_at IS NULL",
                    timestamp(clock.instant()), userId);
        });
    }

    public static String normalizeEmail(String email) {
        if (email == null) throw invalidInput();
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !normalized.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")) throw invalidInput();
        return normalized;
    }

    private static void validatePassword(String password) {
        if (password == null || password.codePointCount(0, password.length()) < 15
                || password.codePointCount(0, password.length()) > 128 || password.indexOf('\0') >= 0) throw invalidInput();
    }

    private static String validatedNickname(String nickname) {
        if (nickname == null) throw invalidInput();
        String chosen = nickname.strip();
        if (chosen.isBlank() || chosen.codePointCount(0, chosen.length()) > 40
                || chosen.codePoints().anyMatch(Character::isISOControl)) throw invalidInput();
        return chosen;
    }

    private static AuthFailure invalidInput() {
        return new AuthFailure(400, "INVALID_INPUT", "请检查邮箱、昵称及密码长度（15–128个字符）");
    }

    private Optional<AccountRow> accountByEmail(String email) {
        return accountRows("email = ?", email).stream().findFirst();
    }

    private AccountRow lockAccount(UUID userId) {
        return accountRows("id = ?", userId).stream().findFirst().orElseThrow(AuthFailure::invalidCredentials);
    }

    private List<AccountRow> accountRows(String condition, Object parameter) {
        return jdbc.query("SELECT id, email, password_hash, nickname, status FROM accounts WHERE " + condition + " FOR UPDATE",
                (row, rowNumber) -> new AccountRow(row.getObject("id", UUID.class), row.getString("email"),
                        row.getString("password_hash"), row.getString("nickname"), row.getString("status")), parameter);
    }

    private void issueAccountToken(AccountRow account, String purpose, Duration lifetime) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(lifetime);
        jdbc.update("UPDATE account_tokens SET consumed_at = GREATEST(created_at, ?) WHERE account_id = ? AND purpose = ? AND consumed_at IS NULL",
                timestamp(now), account.id(), purpose);
        String token = Secrets.token();
        jdbc.update("INSERT INTO account_tokens (id, account_id, purpose, token_digest, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), account.id(), purpose, Secrets.digest(token), timestamp(now), timestamp(expiresAt));
        String subject = purpose.equals("VERIFY_EMAIL") ? "UNO 邮箱验证" : "UNO 重置密码";
        mail.enqueue(account.email(), subject, "请仅在本应用中输入以下凭证。不要转发给他人。\nToken: " + token
                + "\n有效期至：" + expiresAt + "\n如非本人申请，请忽略本邮件。仅最新一次申请的凭证有效。", expiresAt);
    }

    private AccountRow consumeAccountToken(String token, String purpose, String requiredStatus) {
        if (!Secrets.validToken(token)) throw AuthFailure.invalidToken();
        var owners = jdbc.query("SELECT account_id FROM account_tokens WHERE token_digest = ? AND purpose = ?",
                (row, rowNumber) -> row.getObject(1, UUID.class), Secrets.digest(token), purpose);
        if (owners.isEmpty()) throw AuthFailure.invalidToken();
        AccountRow account = lockAccount(owners.get(0));
        var valid = jdbc.queryForList("SELECT id FROM account_tokens WHERE token_digest = ? AND purpose = ? AND consumed_at IS NULL AND expires_at > ? FOR UPDATE",
                Secrets.digest(token), purpose, timestamp(clock.instant()));
        if (valid.isEmpty() || !account.status().equals(requiredStatus)) throw AuthFailure.invalidToken();
        jdbc.update("UPDATE account_tokens SET consumed_at = GREATEST(created_at, ?) WHERE account_id = ? AND purpose = ? AND consumed_at IS NULL",
                timestamp(clock.instant()), account.id(), purpose);
        return account;
    }

    private String issueRefresh(UUID sessionId, Instant expiresAt) {
        String token = Secrets.token();
        jdbc.update("INSERT INTO refresh_tokens (id, session_id, token_digest, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), sessionId, Secrets.digest(token), timestamp(clock.instant()), timestamp(expiresAt));
        return token;
    }

    private void revokeAll(UUID userId) {
        jdbc.update("UPDATE sessions SET revoked_at = GREATEST(created_at, ?) WHERE account_id = ? AND revoked_at IS NULL", timestamp(clock.instant()), userId);
    }

    private void revokeSession(UUID sessionId) {
        jdbc.update("UPDATE sessions SET revoked_at = GREATEST(created_at, ?) WHERE id = ? AND revoked_at IS NULL", timestamp(clock.instant()), sessionId);
    }

    private static Timestamp timestamp(Instant instant) { return Timestamp.from(instant); }

    private record AccountRow(UUID id, String email, String hash, String nickname, String status) {
        @Override public String toString() { return "AccountRow[redacted]"; }
    }

    private record RefreshRow(UUID id, UUID sessionId, Instant expiresAt, boolean consumed, boolean revoked, Instant absoluteExpiry) { }

    public record LoginGrant(SessionIdentity identity, String accessToken, String refreshToken, Instant refreshExpiresAt) {
        @Override public String toString() { return "LoginGrant[redacted]"; }
    }
}
