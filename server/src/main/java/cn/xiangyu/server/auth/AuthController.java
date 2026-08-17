package cn.xiangyu.server.auth;

import cn.xiangyu.server.security.SessionService;
import cn.xiangyu.server.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static cn.xiangyu.server.auth.AuthDtos.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;
    private final SessionService sessions;

    public AuthController(AuthService authService, SessionService sessions) {
        this.authService = authService;
        this.sessions = sessions;
    }

    @PostMapping("/sms/send")
    ResponseEntity<SmsCodeResponse> send(@Valid @RequestBody SmsCodeRequest request) {
        return ResponseEntity.accepted().body(authService.sendCode(request));
    }

    @PostMapping("/sms/verify")
    TokenPair verify(@RequestHeader("Idempotency-Key") UUID idempotencyKey,
                     @Valid @RequestBody SmsVerifyRequest request) {
        return authService.verify(request);
    }

    @PostMapping("/token/refresh")
    TokenPair refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request);
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@AuthenticationPrincipal UserPrincipal principal) {
        sessions.logout(principal.sessionId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    ResponseEntity<Void> logoutAll(@AuthenticationPrincipal UserPrincipal principal) {
        sessions.logoutAll(principal.userId());
        return ResponseEntity.noContent().build();
    }
}
