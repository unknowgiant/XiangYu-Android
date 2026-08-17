package cn.xiangyu.server.security;

import cn.xiangyu.server.api.ApiException;
import cn.xiangyu.server.config.SecurityProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class SessionService {
    public record SessionTokens(String accessToken, long expiresIn, String refreshToken,
                                long refreshExpiresIn, UUID sessionId) { }
    public record DeviceSession(UUID deviceId, String name, String platform, Instant createdAt,
                                Instant lastSeenAt, boolean current) { }

    private final JdbcTemplate jdbc;
    private final CryptoService crypto;
    private final Duration accessLifetime;
    private final Duration refreshLifetime;

    public SessionService(JdbcTemplate jdbc, CryptoService crypto, SecurityProperties properties) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.accessLifetime = Duration.ofMinutes(properties.accessTokenMinutes());
        this.refreshLifetime = Duration.ofDays(properties.refreshTokenDays());
    }

    @Transactional
    public SessionTokens issue(UUID userId, UUID deviceId, String deviceName,
                               String platform, String appVersion) {
        Instant now = Instant.now();
        jdbc.update("UPDATE sessions SET revoked_at=? WHERE user_id=? AND device_id=? AND revoked_at IS NULL",
                Timestamp.from(now), userId, deviceId);
        UUID sessionId = UUID.randomUUID();
        String accessToken = crypto.randomToken();
        String refreshToken = crypto.randomToken();
        jdbc.update("""
                INSERT INTO sessions(id,user_id,device_id,device_name,platform,app_version,
                  access_token_hash,access_expires_at,refresh_token_hash,refresh_expires_at,
                  created_at,last_seen_at,revoked_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,NULL)
                """, sessionId, userId, deviceId, trim(deviceName, 80), platform, trim(appVersion, 30),
                crypto.sha256(accessToken), Timestamp.from(now.plus(accessLifetime)),
                crypto.sha256(refreshToken), Timestamp.from(now.plus(refreshLifetime)),
                Timestamp.from(now), Timestamp.from(now));
        return new SessionTokens(accessToken, accessLifetime.toSeconds(), refreshToken,
                refreshLifetime.toSeconds(), sessionId);
    }

    @Transactional
    public SessionTokens refresh(String refreshToken, UUID deviceId) {
        String hash = crypto.sha256(refreshToken);
        List<SessionRow> rows = jdbc.query("""
                SELECT s.id,s.user_id,s.device_id,s.device_name,s.platform,s.app_version
                FROM sessions s JOIN users u ON u.id=s.user_id
                WHERE s.refresh_token_hash=? AND s.device_id=? AND s.revoked_at IS NULL
                  AND s.refresh_expires_at>now() AND u.status='ACTIVE'
                FOR UPDATE
                """, (rs, rowNum) -> new SessionRow(
                rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("device_id", UUID.class), rs.getString("device_name"),
                rs.getString("platform"), rs.getString("app_version")), hash, deviceId);
        if (rows.size() != 1) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "登录状态已失效，请重新登录");
        }
        SessionRow row = rows.get(0);
        Instant now = Instant.now();
        String newAccess = crypto.randomToken();
        String newRefresh = crypto.randomToken();
        jdbc.update("""
                UPDATE sessions SET access_token_hash=?,access_expires_at=?,refresh_token_hash=?,
                  refresh_expires_at=?,last_seen_at=? WHERE id=?
                """, crypto.sha256(newAccess), Timestamp.from(now.plus(accessLifetime)),
                crypto.sha256(newRefresh), Timestamp.from(now.plus(refreshLifetime)),
                Timestamp.from(now), row.id());
        return new SessionTokens(newAccess, accessLifetime.toSeconds(), newRefresh,
                refreshLifetime.toSeconds(), row.id());
    }

    public Optional<UserPrincipal> authenticate(String accessToken) {
        if (accessToken == null || accessToken.length() < 32 || accessToken.length() > 256) {
            return Optional.empty();
        }
        List<UserPrincipal> rows = jdbc.query("""
                SELECT s.user_id,s.id,s.device_id,u.status
                FROM sessions s JOIN users u ON u.id=s.user_id
                WHERE s.access_token_hash=? AND s.revoked_at IS NULL
                  AND s.access_expires_at>now() AND u.status IN ('ACTIVE','PENDING_DELETE')
                """, (rs, rowNum) -> new UserPrincipal(
                rs.getObject("user_id", UUID.class), rs.getObject("id", UUID.class),
                rs.getObject("device_id", UUID.class), rs.getString("status")),
                crypto.sha256(accessToken));
        if (rows.size() != 1) return Optional.empty();
        jdbc.update("UPDATE sessions SET last_seen_at=now() WHERE id=? AND last_seen_at<now()-interval '5 minutes'",
                rows.get(0).sessionId());
        return Optional.of(rows.get(0));
    }

    public void logout(UUID sessionId) {
        jdbc.update("UPDATE sessions SET revoked_at=now() WHERE id=? AND revoked_at IS NULL", sessionId);
    }

    public void logoutAll(UUID userId) {
        jdbc.update("UPDATE sessions SET revoked_at=now() WHERE user_id=? AND revoked_at IS NULL", userId);
    }

    public List<DeviceSession> devices(UUID userId, UUID currentSessionId) {
        return jdbc.query("""
                SELECT id,device_id,device_name,platform,created_at,last_seen_at
                FROM sessions WHERE user_id=? AND revoked_at IS NULL AND refresh_expires_at>now()
                ORDER BY last_seen_at DESC
                """, (rs, rowNum) -> new DeviceSession(
                rs.getObject("device_id", UUID.class), rs.getString("device_name"),
                rs.getString("platform"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("last_seen_at").toInstant(),
                currentSessionId.equals(rs.getObject("id", UUID.class))), userId);
    }

    public boolean revokeDevice(UUID userId, UUID deviceId) {
        return jdbc.update("UPDATE sessions SET revoked_at=now() WHERE user_id=? AND device_id=? AND revoked_at IS NULL",
                userId, deviceId) > 0;
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record SessionRow(UUID id, UUID userId, UUID deviceId, String deviceName,
                              String platform, String appVersion) { }
}
