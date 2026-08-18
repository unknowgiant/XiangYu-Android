package cn.xiangyu.server.sync;

import cn.xiangyu.server.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static cn.xiangyu.server.sync.SyncDtos.EntityType.NOTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncPayloadValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SyncPayloadValidator validator = new SyncPayloadValidator();

    @Test
    void validatesNoteMetadataAndLength() throws Exception {
        var metadata = validator.validate(NOTE, mapper.readTree("""
                {"subjectId":"310100-food-1","cityCode":"310100","category":0,
                 "titleSnapshot":"生煎馒头","content":"上午去排队。"}
                """));
        assertThat(metadata.subjectId()).isEqualTo("310100-food-1");
        assertThat(metadata.category()).isZero();
    }

    @Test
    void rejectsUnexpectedFields() throws Exception {
        assertThatThrownBy(() -> validator.validate(NOTE, mapper.readTree("""
                {"subjectId":"a","cityCode":"310100","category":0,
                 "titleSnapshot":"标题","content":"正文","public":true}
                """))).isInstanceOf(ApiException.class);
    }
}
