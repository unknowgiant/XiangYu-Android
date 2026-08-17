package cn.xiangyu.server.auth;

import cn.xiangyu.server.account.UserView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public final class AuthDtos {
    private AuthDtos() { }

    public record SmsCodeRequest(
            @NotBlank @Pattern(regexp = "\\+86") String countryCode,
            @NotBlank @Pattern(regexp = "1[3-9][0-9]{9}") String phone,
            @NotNull UUID clientRequestId,
            @Size(max = 2048) String captchaToken) { }

    public record SmsCodeResponse(UUID requestId, long expiresIn, long resendAfter) { }

    public record DeviceInfo(
            @NotNull UUID deviceId,
            @Size(max = 80) String name,
            @NotBlank @Pattern(regexp = "ANDROID") String platform,
            @NotBlank @Size(max = 30) String appVersion) { }

    public record SmsVerifyRequest(
            @NotNull UUID requestId,
            @NotBlank @Pattern(regexp = "[0-9]{4,8}") String code,
            @NotNull @Valid DeviceInfo device,
            @NotBlank @Size(max = 40) String acceptedTermsVersion,
            @NotBlank @Size(max = 40) String acceptedPrivacyVersion) { }

    public record RefreshRequest(
            @NotBlank @Size(min = 32, max = 2048) String refreshToken,
            @NotNull UUID deviceId) { }

    public record TokenPair(String accessToken, long expiresIn, String refreshToken,
                            long refreshExpiresIn, UserView user) { }
}
