package cn.xiangyu.server.community;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityStatusControllerTest {
    @Test
    void remainsUnavailableUntilTheFeatureFlagIsEnabled() {
        var status = new CommunityStatusController(new CommunityProperties(false, 2000)).status();

        assertThat(status.enabled()).isFalse();
        assertThat(status.state()).isEqualTo("NOT_OPEN");
        assertThat(status.maxContentLength()).isEqualTo(2000);
    }
}
