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
                String name = value.getString("name_zh");
                LocalData.Item item = new LocalData.Item(city.code + "-5a-" + value.optString("slug", String.valueOf(i)),
                    name, scenicDescription(name),
                    scenicCategory(name) + " · 国家5A · Lore Routes公开数据 · 坐标来源 " + coordinateSource,
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

    private static String scenicCategory(String name) {
        if (containsAny(name, "纪念", "革命", "起义", "会址", "旧址", "西柏坡", "烈士")) return "红色学习";
        if (containsAny(name, "动物园", "植物园", "海洋", "乐园", "恐龙", "科技馆")) return "亲子遛娃";
        if (containsAny(name, "山", "湖", "河", "瀑布", "峡", "洞", "草原", "沙漠", "湿地", "森林", "海岛")) return "自然景观";
        return "历史人文";
    }

    private static String scenicDescription(String name) {
        String category = scenicCategory(name);
        String focus;
        if (category.equals("红色学习")) {
            focus = "适合红色研学和历史主题参观，可结合基本陈列、旧址空间与定时讲解理解事件背景。";
        } else if (category.equals("亲子遛娃")) {
            focus = "适合亲子半日或一日游，建议提前核对儿童适龄项目、预约场次、休息区与推车通行条件。";
        } else if (category.equals("自然景观")) {
            focus = "以自然地貌、生态环境或山水景观为核心看点，游览前需关注天气、步行强度、补给与返程交通。";
        } else {
            focus = "以历史建筑、地方文化或传统聚落为主要看点，可结合官方讲解了解空间沿革与文化背景。";
        }
        return name + "为国家 5A 级旅游景区。" + focus
            + " 建议预留半日至一日；开放时间、门票、预约入口、临时管制和交通安排可能变化，出发前通过景区官方渠道复核。";
    }

    private static boolean containsAny(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }
}
