package cn.xiangyu.server.security;

import java.util.UUID;

public record UserPrincipal(UUID userId, UUID sessionId, UUID deviceId, String status) {
}
