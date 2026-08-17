package cn.xiangyu.server.sync;

import cn.xiangyu.server.security.UserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static cn.xiangyu.server.sync.SyncDtos.*;

@Validated
@RestController
@RequestMapping("/api/v1/sync")
public class SyncController {
    private final SyncService syncService;

    public SyncController(SyncService syncService) {
        this.syncService = syncService;
    }

    @PostMapping
    SyncResponse synchronize(@AuthenticationPrincipal UserPrincipal principal,
                             @RequestHeader("Idempotency-Key") UUID idempotencyKey,
                             @Valid @RequestBody SyncRequest request) {
        return syncService.synchronize(principal, request);
    }

    @GetMapping("/bootstrap")
    ChangePage bootstrap(@AuthenticationPrincipal UserPrincipal principal,
                         @RequestParam(required = false) @Size(max = 2048) String cursor,
                         @RequestParam(defaultValue = "200") @Min(1) @Max(200) int limit) {
        return syncService.bootstrap(principal, cursor, limit);
    }
}
