package cn.xiangyu.server.community;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/community")
public class CommunityStatusController {
    private final CommunityProperties properties;

    public CommunityStatusController(CommunityProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/status")
    CommunityStatus status() {
        return new CommunityStatus(
                properties.enabled(),
                properties.enabled() ? "AVAILABLE" : "NOT_OPEN",
                properties.maxContentLength());
    }

    public record CommunityStatus(boolean enabled, String state, int maxContentLength) { }
}
