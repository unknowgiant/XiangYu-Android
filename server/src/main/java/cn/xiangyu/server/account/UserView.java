package cn.xiangyu.server.account;

import java.time.Instant;
import java.util.UUID;

public record UserView(UUID id, String status, String displayName, String maskedPhone,
                       Instant createdAt, Instant deleteAfter) {
}
