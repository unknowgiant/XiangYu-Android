package cn.xiangyu.server.security;

import cn.xiangyu.server.config.SecurityProperties;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

public class CryptoServiceTest {
    @Test
    void encryptsAndDecryptsPhoneWithoutDeterministicCiphertext() {
        CryptoService crypto = service();
        String first = crypto.encryptPhone("13800138000");
        String second = crypto.encryptPhone("13800138000");

        assertThat(first).isNotEqualTo(second);
        assertThat(crypto.decryptPhone(first)).isEqualTo("13800138000");
        assertThat(crypto.phoneHmac("13800138000")).hasSize(64);
    }

    public static CryptoService service() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        return new CryptoService(new SecurityProperties(key, key, key, 15, 30));
    }
}
