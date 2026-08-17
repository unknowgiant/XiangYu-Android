package cn.xiangyu.server.security;

import cn.xiangyu.server.config.SecurityProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class CryptoService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final byte[] encryptionKey;
    private final byte[] phoneHmacKey;
    private final byte[] cursorHmacKey;

    public CryptoService(SecurityProperties properties) {
        this.encryptionKey = decodeKey(properties.phoneEncryptionKey(), "XIANGYU_PHONE_ENCRYPTION_KEY");
        this.phoneHmacKey = decodeKey(properties.phoneHmacKey(), "XIANGYU_PHONE_HMAC_KEY");
        this.cursorHmacKey = decodeKey(properties.cursorHmacKey(), "XIANGYU_CURSOR_HMAC_KEY");
    }

    public String encryptPhone(String phone) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(phone.getBytes(StandardCharsets.UTF_8));
            return URL_ENCODER.encodeToString(ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv).put(encrypted).array());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to encrypt phone", exception);
        }
    }

    public String decryptPhone(String encoded) {
        try {
            byte[] value = URL_DECODER.decode(encoded);
            byte[] iv = Arrays.copyOfRange(value, 0, 12);
            byte[] encrypted = Arrays.copyOfRange(value, 12, value.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
                    new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to decrypt phone", exception);
        }
    }

    public String phoneHmac(String value) {
        return hmacHex(phoneHmacKey, value.getBytes(StandardCharsets.UTF_8));
    }

    public String cursorHmac(byte[] value) {
        return hmacHex(cursorHmacKey, value);
    }

    public String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public String randomToken() {
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        return URL_ENCODER.encodeToString(value);
    }

    public String randomSmsCode() {
        return "%06d".formatted(RANDOM.nextInt(1_000_000));
    }

    public boolean constantTimeEquals(String first, String second) {
        return MessageDigest.isEqual(first.getBytes(StandardCharsets.US_ASCII),
                second.getBytes(StandardCharsets.US_ASCII));
    }

    private static String hmacHex(byte[] key, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value));
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private static byte[] decodeKey(String encoded, String name) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException(name + " must be configured");
        }
        try {
            byte[] key = Base64.getDecoder().decode(encoded);
            if (key.length != 32) throw new IllegalArgumentException();
            return key;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(name + " must be a Base64-encoded 32-byte key");
        }
    }
}
