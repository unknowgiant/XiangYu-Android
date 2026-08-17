package cn.xiangyu.server.sync;

import cn.xiangyu.server.api.ApiException;
import cn.xiangyu.server.security.CryptoService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class CursorCodec {
    private final CryptoService cryptoService;

    public CursorCodec(CryptoService cryptoService) {
        this.cryptoService = cryptoService;
    }

    public String encode(long sequence) {
        byte[] payload = ByteBuffer.allocate(Long.BYTES).putLong(sequence).array();
        String signature = cryptoService.cursorHmac(payload).substring(0, 32);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (sequence + "." + signature).getBytes(StandardCharsets.US_ASCII));
    }

    public long decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            String[] parts = decoded.split("\\.", 2);
            long sequence = Long.parseLong(parts[0]);
            if (sequence < 0 || parts.length != 2) throw new IllegalArgumentException();
            byte[] payload = ByteBuffer.allocate(Long.BYTES).putLong(sequence).array();
            String expected = cryptoService.cursorHmac(payload).substring(0, 32);
            if (!cryptoService.constantTimeEquals(expected, parts[1])) throw new IllegalArgumentException();
            return sequence;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "同步游标无效，请重新获取数据");
        }
    }
}
