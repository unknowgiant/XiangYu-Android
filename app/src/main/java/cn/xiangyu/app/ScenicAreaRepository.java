package cn.xiangyu.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ScenicAreaRepository {
    private final Map<String, List<LocalData.Item>> byCityCode = new HashMap<>();

    ScenicAreaRepository(Context context, CityRepository cities) {
        for (CityRepository.City city : cities.all()) byCityCode.put(city.code, new ArrayList<>());
        int count = 0;
        try (InputStream input = context.getAssets().open("china_5a_scenic_areas.json")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            JSONArray records = new JSONArray(output.toString(StandardCharsets.UTF_8.name()));
            for (int i = 0; i < records.length(); i++) {
                JSONObject value = records.getJSONObject(i);
                double lat = value.getDouble("latitude");
                double lon = value.getDouble("longitude");
                CityRepository.City city = exactCity(cities, value.optString("province_zh"),
                    value.optString("city_zh"));
                if (city == null) city = cities.nearestInProvince(value.optString("province_zh"), lat, lon);
                if (city == null) throw new IllegalStateException("Unmatched scenic area: " + value.optString("name_zh"));
                String coordinateSource = value.optString("coordinate_source", "公开资料");
                LocalData.Item item = new LocalData.Item(city.code + "-5a-" + value.optString("slug", String.valueOf(i)),
                    value.getString("name_zh"),
                    "国家 5A 级旅游景区。开放时间、门票、预约和交通安排可能变化，出发前通过景区官方渠道复核。",
                    "国家5A · Lore Routes公开数据 · 坐标来源 " + coordinateSource,
                    0xff47748a, "5A", lat, lon);
                byCityCode.get(city.code).add(item);
                count++;
            }
        } catch (Exception error) {
            throw new IllegalStateException("Cannot load 5A scenic-area dataset", error);
        }
        if (count != 373) throw new IllegalStateException("Expected 373 scenic areas, got " + count);
    }

    List<LocalData.Item> forCity(CityRepository.City city) {
        return new ArrayList<>(byCityCode.get(city.code));
    }

    private static CityRepository.City exactCity(CityRepository cities, String province, String officialName) {
        for (CityRepository.City city : cities.all()) {
            if (city.province.equals(province) && city.officialName.equals(officialName)) return city;
        }
        return null;
    }
}
