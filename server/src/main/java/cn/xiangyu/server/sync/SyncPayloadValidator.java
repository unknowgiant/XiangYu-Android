package cn.xiangyu.server.sync;

import cn.xiangyu.server.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

import static cn.xiangyu.server.sync.SyncDtos.EntityType;

@Component
public class SyncPayloadValidator {
    public record Metadata(String subjectId, String cityCode, Integer category) { }

    public Metadata validate(EntityType type, JsonNode payload) {
        if (payload == null || !payload.isObject()) invalid("同步内容不能为空");
        return switch (type) {
            case FAVORITE -> favorite(payload);
            case NOTE -> note(payload);
            case CUSTOM_ITEM -> customItem(payload);
            case JOURNAL -> journal(payload);
        };
    }

    private Metadata favorite(JsonNode value) {
        allowed(value, "subjectId", "cityCode", "category", "titleSnapshot",
                "subtitleSnapshot", "metaSnapshot", "color", "mark");
        String subject = requiredText(value, "subjectId", 160);
        String city = requiredText(value, "cityCode", 20);
        int category = category(value);
        requiredText(value, "titleSnapshot", 160);
        optionalText(value, "subtitleSnapshot", 2000);
        optionalText(value, "metaSnapshot", 500);
        optionalText(value, "mark", 4);
        return new Metadata(subject, city, category);
    }

    private Metadata note(JsonNode value) {
        allowed(value, "subjectId", "cityCode", "category", "titleSnapshot", "content");
        String subject = requiredText(value, "subjectId", 160);
        String city = requiredText(value, "cityCode", 20);
        int category = category(value);
        requiredText(value, "titleSnapshot", 160);
        requiredText(value, "content", 12000);
        return new Metadata(subject, city, category);
    }

    private Metadata customItem(JsonNode value) {
        allowed(value, "legacyId", "cityCode", "category", "title", "subtitle", "meta",
                "color", "mark", "latitude", "longitude");
        optionalText(value, "legacyId", 160);
        String city = requiredText(value, "cityCode", 20);
        int category = category(value);
        requiredText(value, "title", 160);
        text(value, "subtitle", 2000, true);
        text(value, "meta", 500, true);
        optionalText(value, "mark", 4);
        optionalNumber(value, "latitude", -90, 90);
        optionalNumber(value, "longitude", -180, 180);
        return new Metadata(null, city, category);
    }

    private Metadata journal(JsonNode value) {
        allowed(value, "title", "content", "travelDate", "cityCode", "subjectIds");
        requiredText(value, "title", 160);
        requiredText(value, "content", 12000);
        String city = optionalText(value, "cityCode", 20);
        optionalText(value, "travelDate", 10);
        JsonNode subjectIds = value.get("subjectIds");
        if (subjectIds != null && !subjectIds.isNull()) {
            if (!subjectIds.isArray() || subjectIds.size() > 50) invalid("关联条目数量不正确");
            Set<String> unique = new HashSet<>();
            for (JsonNode subject : subjectIds) {
                if (!subject.isTextual() || subject.asText().isBlank() || subject.asText().length() > 160
                        || !unique.add(subject.asText())) invalid("关联条目格式不正确");
            }
        }
        return new Metadata(null, city, null);
    }

    private static int category(JsonNode value) {
        JsonNode category = value.get("category");
        if (category == null || !category.canConvertToInt() || category.intValue() < 0
                || category.intValue() > 4) invalid("内容分类不正确");
        return category.intValue();
    }

    private static String requiredText(JsonNode value, String name, int max) {
        return text(value, name, max, false);
    }

    private static String optionalText(JsonNode value, String name, int max) {
        JsonNode node = value.get(name);
        if (node == null || node.isNull()) return null;
        return text(value, name, max, true);
    }

    private static String text(JsonNode value, String name, int max, boolean emptyAllowed) {
        JsonNode node = value.get(name);
        if (node == null || !node.isTextual() || node.asText().length() > max
                || (!emptyAllowed && node.asText().isBlank())) invalid("字段 " + name + " 格式不正确");
        return node.asText();
    }

    private static void optionalNumber(JsonNode value, String name, double min, double max) {
        JsonNode node = value.get(name);
        if (node != null && !node.isNull()
                && (!node.isNumber() || node.doubleValue() < min || node.doubleValue() > max)) {
            invalid("字段 " + name + " 格式不正确");
        }
    }

    private static void allowed(JsonNode value, String... names) {
        Set<String> allowed = Set.of(names);
        value.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) invalid("存在不支持的字段");
        });
    }

    private static void invalid(String message) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SYNC_PAYLOAD", message);
    }
}
