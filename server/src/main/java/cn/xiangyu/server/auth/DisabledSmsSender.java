package cn.xiangyu.server.auth;

import cn.xiangyu.server.api.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "xiangyu.sms", name = "mode", havingValue = "disabled", matchIfMissing = true)
public class DisabledSmsSender implements SmsSender {
    @Override
    public void sendLoginCode(String phone, String code) {
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SMS_NOT_CONFIGURED",
                "短信登录正在配置中，请稍后再试");
    }
}
