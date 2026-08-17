package cn.xiangyu.server.sync;

import cn.xiangyu.server.api.ApiException;
import cn.xiangyu.server.security.CryptoServiceTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorCodecTest {
    @Test
    void roundTripsAndRejectsTampering() {
        CursorCodec codec = new CursorCodec(CryptoServiceTest.service());
        String cursor = codec.encode(987654321L);

        assertThat(codec.decode(cursor)).isEqualTo(987654321L);
        assertThatThrownBy(() -> codec.decode(cursor.substring(0, cursor.length() - 1) + "A"))
                .isInstanceOf(ApiException.class);
    }
}
