package cn.xiangyu.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("xiangyu.security")
public record SecurityProperties(
        String phoneEncryptionKey,
        String phoneHmacKey,
        String cursorHmacKey,
        long accessTokenMinutes,
        long refreshTokenDays) {
}
