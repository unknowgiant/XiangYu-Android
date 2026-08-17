package cn.xiangyu.server.account;

import cn.xiangyu.server.api.ApiException;
import cn.xiangyu.server.auth.AuthService;
import cn.xiangyu.server.security.SessionService;
import cn.xiangyu.server.security.UserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me")
public class AccountController {
    public record UpdateProfileRequest(@NotBlank @Size(max = 40) String displayName) { }

    private final JdbcTemplate jdbc;
    private final AuthService authService;
    private final SessionService sessions;

    public AccountController(JdbcTemplate jdbc, AuthService authService, SessionService sessions) {
        this.jdbc = jdbc;
        this.authService = authService;
        this.sessions = sessions;
    }

    @GetMapping
    UserView get(@AuthenticationPrincipal UserPrincipal principal) {
        return authService.user(principal.userId());
    }

    @PatchMapping
    UserView update(@AuthenticationPrincipal UserPrincipal principal,
                    @Valid @RequestBody UpdateProfileRequest request) {
        requireActive(principal);
        jdbc.update("UPDATE users SET display_name=?,updated_at=now() WHERE id=?",
                request.displayName().trim(), principal.userId());
        return authService.user(principal.userId());
    }

    @GetMapping("/devices")
    Map<String, List<SessionService.DeviceSession>> devices(
            @AuthenticationPrincipal UserPrincipal principal) {
        return Map.of("devices", sessions.devices(principal.userId(), principal.sessionId()));
    }

    @DeleteMapping("/devices/{deviceId}")
    ResponseEntity<Void> revoke(@AuthenticationPrincipal UserPrincipal principal,
                                @PathVariable UUID deviceId) {
        if (!sessions.revokeDevice(principal.userId(), deviceId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "未找到该登录设备");
        }
        return ResponseEntity.noContent().build();
    }

    private static void requireActive(UserPrincipal principal) {
        if (!"ACTIVE".equals(principal.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_NOT_ACTIVE", "账号当前不可修改");
        }
    }
}
