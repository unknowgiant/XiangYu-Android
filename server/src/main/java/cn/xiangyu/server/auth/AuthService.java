package cn.xiangyu.server.auth;

import cn.xiangyu.server.account.UserView;
import cn.xiangyu.server.api.ApiException;
import cn.xiangyu.server.security.CryptoService;
import cn.xiangyu.server.security.SessionService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static cn.xiangyu.server.auth.AuthDtos.*;

@Service
public class AuthService {
    private static final Duration CODE_LIFETIME = Duration.ofMinutes(5);
    private static final Duration RESEND_DELAY = Duration.ofSeconds(60);

    private final JdbcTemplate jdbc;
    private final CryptoService crypto;
    private final SmsSender smsSender;
    private final SessionService sessions;

    public AuthService(JdbcTemplate jdbc, CryptoService crypto, SmsSender smsSender,
                       SessionService sessions) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.smsSender = smsSender;
        this.sessions = sessions;
    }

    @Transactional
    public SmsCodeResponse sendCode(SmsCodeRequest request) {
        List<SmsCodeResponse> existing = jdbc.query("""
                SELECT id,expires_at,resend_after FROM sms_challenges WHERE client_request_id=?
                """, (rs, rowNum) -> response(rs.getObject("id", UUID.class),
                rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("resend_after").toInstant()),
                request.clientRequestId());
        if (!existing.isEmpty()) return existing.get(0);

        String phoneHmac = crypto.phoneHmac(request.phone());
        Instant now = Instant.now();
        List<Instant> last = jdbc.query("""
                SELECT created_at FROM sms_challenges WHERE phone_hmac=?
                ORDER BY created_at DESC LIMIT 1
                """, (rs, rowNum) -> rs.getTimestamp(1).toInstant(), phoneHmac);
        if (!last.isEmpty() && last.get(0).plus(RESEND_DELAY).isAfter(now)) {
            int retry = (int) Math.max(1, Duration.between(now, last.get(0).plus(RESEND_DELAY)).toSeconds());
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SMS_RATE_LIMITED",
                    "验证码发送过于频繁，请稍后重试", retry);
        }
        Integer hourly = jdbc.queryForObject("""
                SELECT count(*) FROM sms_challenges WHERE phone_hmac=? AND created_at>now()-interval '1 hour'
                """, Integer.class, phoneHmac);
        if (hourly != null && hourly >= 10) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SMS_RATE_LIMITED",
                    "验证码请求次数过多，请稍后重试", 3600);
        }

        UUID requestId = UUID.randomUUID();
        String code = crypto.randomSmsCode();
        smsSender.sendLoginCode(request.phone(), code);
        jdbc.update("""
                INSERT INTO sms_challenges(id,purpose,phone_hmac,phone_ciphertext,code_hash,
                  client_request_id,created_at,expires_at,resend_after,attempts,consumed_at)
                VALUES (?,'LOGIN',?,?,?,?,?,?,?,?,NULL)
                """, requestId, phoneHmac, crypto.encryptPhone(request.phone()),
                crypto.phoneHmac(requestId + "." + code), request.clientRequestId(),
                Timestamp.from(now), Timestamp.from(now.plus(CODE_LIFETIME)),
                Timestamp.from(now.plus(RESEND_DELAY)), 0);
        return response(requestId, now.plus(CODE_LIFETIME), now.plus(RESEND_DELAY));
    }

    @Transactional
    public TokenPair verify(SmsVerifyRequest request) {
        List<Challenge> challenges = jdbc.query("""
                SELECT id,phone_hmac,phone_ciphertext,code_hash,expires_at,attempts,consumed_at
                FROM sms_challenges WHERE id=? FOR UPDATE
                """, (rs, rowNum) -> new Challenge(
                rs.getObject("id", UUID.class), rs.getString("phone_hmac"),
                rs.getString("phone_ciphertext"), rs.getString("code_hash"),
                rs.getTimestamp("expires_at").toInstant(), rs.getInt("attempts"),
                rs.getTimestamp("consumed_at") == null ? null : rs.getTimestamp("consumed_at").toInstant()),
                request.requestId());
        if (challenges.size() != 1) throw invalidCode();
        Challenge challenge = challenges.get(0);
        if (challenge.consumedAt() != null || challenge.expiresAt().isBefore(Instant.now())
                || challenge.attempts() >= 5) throw invalidCode();

        jdbc.update("UPDATE sms_challenges SET attempts=attempts+1 WHERE id=?", challenge.id());
        String actual = crypto.phoneHmac(challenge.id() + "." + request.code());
        if (!crypto.constantTimeEquals(challenge.codeHash(), actual)) throw invalidCode();

        UUID userId = findOrCreateUser(challenge);
        Instant now = Instant.now();
        jdbc.update("UPDATE sms_challenges SET consumed_at=? WHERE id=?", Timestamp.from(now), challenge.id());
        jdbc.update("""
                INSERT INTO consent_acceptances(user_id,terms_version,privacy_version,accepted_at)
                VALUES (?,?,?,?) ON CONFLICT DO NOTHING
                """, userId, request.acceptedTermsVersion(), request.acceptedPrivacyVersion(), Timestamp.from(now));

        DeviceInfo device = request.device();
        SessionService.SessionTokens tokens = sessions.issue(userId, device.deviceId(), device.name(),
                device.platform(), device.appVersion());
        return tokenPair(tokens, user(userId));
    }

    public TokenPair refresh(RefreshRequest request) {
        SessionService.SessionTokens tokens = sessions.refresh(request.refreshToken(), request.deviceId());
        UUID userId = jdbc.queryForObject("SELECT user_id FROM sessions WHERE id=?", UUID.class,
                tokens.sessionId());
        return tokenPair(tokens, user(userId));
    }

    public UserView user(UUID userId) {
        return jdbc.queryForObject("""
                SELECT u.id,u.status,u.display_name,u.created_at,u.delete_after,i.identifier_ciphertext
                FROM users u LEFT JOIN auth_identities i ON i.user_id=u.id AND i.provider='PHONE'
                WHERE u.id=?
                """, (rs, rowNum) -> {
            String encrypted = rs.getString("identifier_ciphertext");
            String masked = encrypted == null ? null : mask(crypto.decryptPhone(encrypted));
            Timestamp deleteAfter = rs.getTimestamp("delete_after");
            return new UserView(rs.getObject("id", UUID.class), rs.getString("status"),
                    rs.getString("display_name"), masked, rs.getTimestamp("created_at").toInstant(),
                    deleteAfter == null ? null : deleteAfter.toInstant());
        }, userId);
    }

    private UUID findOrCreateUser(Challenge challenge) {
        List<UUID> users = jdbc.query("""
                SELECT user_id FROM auth_identities WHERE provider='PHONE' AND identifier_hmac=?
                """, (rs, rowNum) -> rs.getObject(1, UUID.class), challenge.phoneHmac());
        if (!users.isEmpty()) return users.get(0);
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO users(id,status,created_at,updated_at) VALUES (?,'ACTIVE',?,?)",
                userId, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO auth_identities(id,user_id,provider,identifier_hmac,identifier_ciphertext,created_at)
                VALUES (?,?,'PHONE',?,?,?)
                """, UUID.randomUUID(), userId, challenge.phoneHmac(), challenge.phoneCiphertext(),
                Timestamp.from(now));
        return userId;
    }

    private static SmsCodeResponse response(UUID id, Instant expiresAt, Instant resendAt) {
        Instant now = Instant.now();
        return new SmsCodeResponse(id, Math.max(0, Duration.between(now, expiresAt).toSeconds()),
                Math.max(0, Duration.between(now, resendAt).toSeconds()));
    }

    private static TokenPair tokenPair(SessionService.SessionTokens tokens, UserView user) {
        return new TokenPair(tokens.accessToken(), tokens.expiresIn(), tokens.refreshToken(),
                tokens.refreshExpiresIn(), user);
    }

    private static ApiException invalidCode() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SMS_CODE", "验证码无效或已过期");
    }

    private static String mask(String phone) {
        return phone.length() == 11 ? phone.substring(0, 3) + "****" + phone.substring(7) : "***";
    }

    private record Challenge(UUID id, String phoneHmac, String phoneCiphertext, String codeHash,
                             Instant expiresAt, int attempts, Instant consumedAt) { }
}
