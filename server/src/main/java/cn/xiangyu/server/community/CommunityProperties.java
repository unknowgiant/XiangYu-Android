package cn.xiangyu.server.community;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "xiangyu.community")
public record CommunityProperties(boolean enabled, int maxContentLength) {
    public CommunityProperties {
        if (maxContentLength < 1 || maxContentLength > 2000) {
            throw new IllegalArgumentException("maxContentLength must be between 1 and 2000");
        }
    }
}
