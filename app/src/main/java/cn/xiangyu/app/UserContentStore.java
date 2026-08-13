package cn.xiangyu.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class UserContentStore {
    static final class ImportResult {
        final int added;
        final int updated;
        final int skipped;

        ImportResult(int added, int updated, int skipped) {
            this.added = added;
            this.updated = updated;
            this.skipped = skipped;
        }

        int total() { return added + updated; }
    }

    static final class Record {
        final String itemId;
        final String cityCode;
        final int category;
        String title;
        String subtitle;
        String meta;
        String note;
        final boolean custom;
        final boolean contentEdited;
        final int color;
        final String mark;
        final double lat;
        final double lon;

        Record(String itemId, String cityCode, int category, String title, String subtitle,
               String meta, String note, boolean custom, boolean contentEdited,
               int color, String mark, double lat, double lon) {
            this.itemId = itemId;
            this.cityCode = cityCode;
            this.category = category;
            this.title = title;
            this.subtitle = subtitle;
            this.meta = meta;
            this.note = note;
            this.custom = custom;
            this.contentEdited = contentEdited;
            this.color = color;
            this.mark = mark;
            this.lat = lat;
            this.lon = lon;
        }

        LocalData.Item asItem() {
            return new LocalData.Item(itemId, title, subtitle, meta, color, mark, lat, lon);
        }
    }

    private final SharedPreferences preferences;
    private final Map<String, Record> records = new LinkedHashMap<>();

    UserContentStore(Context context) {
        preferences = context.getSharedPreferences("user_content", Context.MODE_PRIVATE);
        restore();
    }

    List<LocalData.Item> apply(String cityCode, int category, List<LocalData.Item> base) {
        List<LocalData.Item> result = new ArrayList<>();
        for (LocalData.Item item : base) {
            Record record = records.get(item.id);
            result.add(record == null || !record.contentEdited ? item : record.asItem());
        }
        for (Record record : records.values()) {
            if (record.custom && record.cityCode.equals(cityCode) && record.category == category) {
                result.add(0, record.asItem());
            }
        }
        return result;
    }

    Record find(String itemId) { return records.get(itemId); }

    String note(String itemId) {
        Record record = records.get(itemId);
        return record == null ? "" : record.note;
    }

    boolean isCustom(String itemId) {
        Record record = records.get(itemId);
        return record != null && record.custom;
    }

    LocalData.Item add(String cityCode, int category, String title, String subtitle, String meta) {
        String id = "custom-" + System.currentTimeMillis();
        int[] colors = {0xffc4633f, 0xff537263, 0xff47748a, 0xffa34c3a, 0xff536f78};
        String[] marks = {"食", "俗", "景", "避", "宿"};
        Record record = new Record(id, cityCode, category, title, subtitle, meta, "", true, true,
            colors[Math.max(0, Math.min(4, category))], marks[Math.max(0, Math.min(4, category))],
            Double.NaN, Double.NaN);
        records.put(id, record);
        persist();
        return record.asItem();
    }

    LocalData.Item update(String cityCode, int category, LocalData.Item item,
                          String title, String subtitle, String meta) {
        Record old = records.get(item.id);
        Record record = new Record(item.id, old == null ? cityCode : old.cityCode,
            old == null ? category : old.category, title, subtitle, meta,
            old == null ? "" : old.note, old != null && old.custom, true, item.color, item.mark,
            old == null ? item.lat : old.lat, old == null ? item.lon : old.lon);
        records.put(item.id, record);
        persist();
        return record.asItem();
    }

    void saveNote(String cityCode, int category, LocalData.Item item, String note) {
        Record old = records.get(item.id);
        Record record = new Record(item.id, old == null ? cityCode : old.cityCode,
            old == null ? category : old.category, old == null ? item.title : old.title,
            old == null ? item.subtitle : old.subtitle, old == null ? item.meta : old.meta,
            note, old != null && old.custom, old != null && old.contentEdited,
            old == null ? item.color : old.color,
            old == null ? item.mark : old.mark, old == null ? item.lat : old.lat,
            old == null ? item.lon : old.lon);
        records.put(item.id, record);
        persist();
    }

    void delete(String itemId) {
        records.remove(itemId);
        persist();
    }

    int notebookEntryCount() {
        int count = 0;
        for (Record record : records.values()) if (!record.note.trim().isEmpty()) count++;
        return count;
    }

    String exportNotebook() throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", "xiangyu-notebook");
        root.put("version", 1);
        root.put("exportedAt", System.currentTimeMillis());
        JSONArray entries = new JSONArray();
        for (Record record : records.values()) {
            if (record.note.trim().isEmpty()) continue;
            entries.put(toJson(record));
        }
        root.put("entries", entries);
        return root.toString(2);
    }

    ImportResult importNotebook(String source) throws Exception {
        JSONObject root = new JSONObject(source);
        if (!"xiangyu-notebook".equals(root.optString("format")) || root.optInt("version", 0) != 1) {
            throw new IllegalArgumentException("不是乡遇记事本文件");
        }
        JSONArray entries = root.optJSONArray("entries");
        if (entries == null) throw new IllegalArgumentException("记事本中没有可读取的条目");
        if (entries.length() > 2000) throw new IllegalArgumentException("记事本条目过多");

        int added = 0, updated = 0, skipped = 0;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject value = entries.optJSONObject(i);
            if (value == null) { skipped++; continue; }
            String id = limited(value.optString("id"), 160);
            String city = limited(value.optString("city"), 40);
            String title = limited(value.optString("title"), 160);
            String note = limited(value.optString("note"), 12000);
            int category = value.optInt("category", -1);
            if (id.isEmpty() || city.isEmpty() || title.isEmpty() || note.trim().isEmpty()
                    || category < 0 || category > 4) {
                skipped++;
                continue;
            }

            Record existing = records.get(id);
            if (existing == null) existing = findByPlaceAndTitle(city, category, title);
            if (existing != null) {
                Record merged = new Record(existing.itemId, existing.cityCode, existing.category,
                    existing.title, existing.subtitle, existing.meta, note, existing.custom,
                    existing.contentEdited, existing.color, existing.mark, existing.lat, existing.lon);
                records.put(existing.itemId, merged);
                updated++;
                continue;
            }

            boolean custom = value.optBoolean("custom", false);
            String subtitle = limited(value.optString("subtitle", "共享记事本条目"), 2000);
            String meta = limited(value.optString("meta", "从共享记事本导入"), 500);
            String mark = limited(value.optString("mark", categoryMark(category)), 4);
            int color = value.optInt("color", categoryColor(category));
            double lat = value.optDouble("lat", Double.NaN);
            double lon = value.optDouble("lon", Double.NaN);
            records.put(id, new Record(id, city, category, title, subtitle, meta, note,
                custom, value.optBoolean("edited", custom), color,
                mark.isEmpty() ? categoryMark(category) : mark, lat, lon));
            added++;
        }
        if (added > 0 || updated > 0) persist();
        return new ImportResult(added, updated, skipped);
    }

    private Record findByPlaceAndTitle(String city, int category, String title) {
        for (Record record : records.values()) {
            if (record.cityCode.equals(city) && record.category == category && record.title.equals(title)) return record;
        }
        return null;
    }

    private static String limited(String value, int maxLength) {
        if (value == null) return "";
        if (value.length() > maxLength) throw new IllegalArgumentException("记事本中有内容过长");
        return value;
    }

    private static int categoryColor(int category) {
        return new int[]{0xffc4633f, 0xff537263, 0xff47748a, 0xffa34c3a, 0xff536f78}[category];
    }

    private static String categoryMark(int category) {
        return new String[]{"食", "俗", "景", "避", "宿"}[category];
    }

    private void restore() {
        try {
            JSONArray array = new JSONArray(preferences.getString("records", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject value = array.getJSONObject(i);
                Record record = new Record(value.getString("id"), value.getString("city"),
                    value.getInt("category"), value.getString("title"), value.getString("subtitle"),
                    value.getString("meta"), value.optString("note", ""), value.optBoolean("custom", false),
                    value.optBoolean("edited", false),
                    value.getInt("color"), value.getString("mark"), value.optDouble("lat", Double.NaN),
                    value.optDouble("lon", Double.NaN));
                records.put(record.itemId, record);
            }
        } catch (Exception ignored) { }
    }

    private void persist() {
        JSONArray array = new JSONArray();
        try {
            for (Record record : records.values()) {
                array.put(toJson(record));
            }
            preferences.edit().putString("records", array.toString()).apply();
        } catch (Exception ignored) { }
    }

    private static JSONObject toJson(Record record) throws Exception {
        JSONObject value = new JSONObject();
        value.put("id", record.itemId); value.put("city", record.cityCode);
        value.put("category", record.category); value.put("title", record.title);
        value.put("subtitle", record.subtitle); value.put("meta", record.meta);
        value.put("note", record.note); value.put("custom", record.custom);
        value.put("edited", record.contentEdited);
        value.put("color", record.color); value.put("mark", record.mark);
        if (!Double.isNaN(record.lat) && !Double.isNaN(record.lon)) {
            value.put("lat", record.lat); value.put("lon", record.lon);
        }
        return value;
    }
}
