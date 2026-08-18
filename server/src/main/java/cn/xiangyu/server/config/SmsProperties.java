package cn.xiangyu.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("xiangyu.sms")
public record SmsProperties(String mode) {
}
