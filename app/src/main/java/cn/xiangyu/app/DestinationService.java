package cn.xiangyu.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class DestinationService {
    interface Callback { void onResult(Result result); }

    static final class Result {
        final List<LocalData.Item> sights;
        final List<LocalData.Item> tips;
        final List<LocalData.Item> hotels;
        final boolean fresh;

        Result(List<LocalData.Item> sights, List<LocalData.Item> tips, List<LocalData.Item> hotels, boolean fresh) {
            this.sights = sights;
            this.tips = tips;
            this.hotels = hotels;
            this.fresh = fresh;
        }
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final long CACHE_LIFE = 7L * 24 * 60 * 60 * 1000;
    private static final String[] TIP_WORDS = {"注意", "避免", "小心", "不要", "预约", "排队", "价格", "收费", "交通", "打车", "安全", "高原", "防晒", "营业"};

    static void fetch(Context context, CityRepository.City city, Callback callback) {
        EXECUTOR.execute(() -> {
            SharedPreferences cache = context.getSharedPreferences("destination_cache", Context.MODE_PRIVATE);
            String key = city.code;
            long updatedAt = cache.getLong(key + "_time", 0);
            String cached = cache.getString(key + "_text", "");
            String cachedOsm = cache.getString(key + "_osm", "");
            String cachedHotels = cache.getString(key + "_hotels", "");
            int cacheVersion = cache.getInt(key + "_version", 1);
            if ((!cached.isEmpty() || !cachedOsm.isEmpty()) && cacheVersion >= 6
                    && System.currentTimeMillis() - updatedAt < CACHE_LIFE) {
                Result wiki = parse(city, cached, false);
                List<LocalData.Item> osm = parseOsmCache(city, cachedOsm);
                callback.onResult(new Result(osm.isEmpty() ? wiki.sights : osm, wiki.tips,
                    parseHotelCache(cachedHotels), false));
                return;
            }
            try {
                String title = URLEncoder.encode(city.name, StandardCharsets.UTF_8.name());
                String endpoint = "https://zh.wikivoyage.org/w/api.php?action=query&prop=extracts"
                    + "&explaintext=1&redirects=1&format=json&formatversion=2&titles=" + title;
                HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty("User-Agent", "XiangYuAndroid/1.1 (travel guide client)");
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }
                JSONObject root = new JSONObject(body.toString());
                JSONObject page = root.getJSONObject("query").getJSONArray("pages").getJSONObject(0);
                String extract = page.optString("extract", "");
                if (!extract.isEmpty()) {
                    cache.edit().putString(key + "_text", extract).putLong(key + "_time", System.currentTimeMillis()).apply();
                    Result wiki = parse(city, extract, true);
                    List<LocalData.Item> osmSights = fetchOpenStreetMap(city);
                    List<LocalData.Item> hotels = fetchOpenStreetMapHotels(city);
                    if (!osmSights.isEmpty()) cache.edit().putString(key + "_osm", serializeOsm(osmSights)).putInt(key + "_version", 6).apply();
                    if (!hotels.isEmpty()) cache.edit().putString(key + "_hotels", serializeOsm(hotels)).apply();
                    callback.onResult(new Result(osmSights.isEmpty() ? wiki.sights : osmSights, wiki.tips, hotels, true));
                    return;
                }
                List<LocalData.Item> osmSights = fetchOpenStreetMap(city);
                if (!osmSights.isEmpty()) {
                    cache.edit().putString(key + "_osm", serializeOsm(osmSights))
                        .putInt(key + "_version", 6).putLong(key + "_time", System.currentTimeMillis()).apply();
                    List<LocalData.Item> hotels = fetchOpenStreetMapHotels(city);
                    if (!hotels.isEmpty()) cache.edit().putString(key + "_hotels", serializeOsm(hotels)).apply();
                    callback.onResult(new Result(osmSights, new ArrayList<>(), hotels, true));
                    return;
                }
            } catch (Exception ignored) { }
            List<LocalData.Item> osmSights = fetchOpenStreetMap(city);
            if (!osmSights.isEmpty()) {
                cache.edit().putString(key + "_osm", serializeOsm(osmSights))
                    .putInt(key + "_version", 6).putLong(key + "_time", System.currentTimeMillis()).apply();
                Result wiki = parse(city, cached, false);
                List<LocalData.Item> hotels = fetchOpenStreetMapHotels(city);
                callback.onResult(new Result(osmSights, wiki.tips, hotels, true));
            } else if (!cached.isEmpty() || !cachedOsm.isEmpty()) {
                Result wiki = parse(city, cached, false);
                List<LocalData.Item> osm = parseOsmCache(city, cachedOsm);
                callback.onResult(new Result(osm.isEmpty() ? wiki.sights : osm, wiki.tips,
                    parseHotelCache(cachedHotels), false));
            } else callback.onResult(new Result(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), false));
        });
    }

    private static String serializeOsm(List<LocalData.Item> items) {
        JSONArray array = new JSONArray();
        try {
            for (LocalData.Item item : items) {
                JSONObject value = new JSONObject();
                value.put("id", item.id); value.put("title", item.title); value.put("subtitle", item.subtitle);
                if (item.hasLocation()) { value.put("lat", item.lat); value.put("lon", item.lon); }
                array.put(value);
            }
        } catch (Exception ignored) { }
        return array.toString();
    }

    private static List<LocalData.Item> parseOsmCache(CityRepository.City city, String json) {
        List<LocalData.Item> result = new ArrayList<>();
        if (json.isEmpty()) return result;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject value = array.getJSONObject(i);
                result.add(new LocalData.Item(value.getString("id"), value.getString("title"),
                    value.getString("subtitle"), "OpenStreetMap · 缓存资料", 0xff47748a, "图",
                    value.optDouble("lat", Double.NaN), value.optDouble("lon", Double.NaN)));
            }
        } catch (Exception ignored) { }
        return result;
    }

    private static List<LocalData.Item> parseHotelCache(String json) {
        List<LocalData.Item> result = new ArrayList<>();
        if (json.isEmpty()) return result;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject value = array.getJSONObject(i);
                result.add(new LocalData.Item(value.getString("id"), value.getString("title"),
                    value.getString("subtitle"), "OpenStreetMap · 住宿参考", 0xff536f78, "宿"));
            }
        } catch (Exception ignored) { }
        return result;
    }

    private static List<LocalData.Item> fetchOpenStreetMapHotels(CityRepository.City city) {
        List<LocalData.Item> budget = new ArrayList<>();
        List<LocalData.Item> unclassified = new ArrayList<>();
        List<LocalData.Item> upscale = new ArrayList<>();
        try {
            String query = "[out:json][timeout:10];(node(around:18000," + city.lat + "," + city.lon
                + ")[tourism~\"hotel|hostel|guest_house|motel\"][name];way(around:18000," + city.lat + "," + city.lon
                + ")[tourism~\"hotel|hostel|guest_house|motel\"][name];);out tags center 40;";
            String endpoint = "https://overpass-api.de/api/interpreter?data="
                + URLEncoder.encode(query, StandardCharsets.UTF_8.name());
            HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", "XiangYuAndroid/1.2 (OpenStreetMap lodging client)");
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            JSONArray elements = new JSONObject(body.toString()).optJSONArray("elements");
            if (elements == null) return budget;
            for (int i = 0; i < elements.length(); i++) {
                JSONObject element = elements.getJSONObject(i);
                JSONObject tags = element.optJSONObject("tags");
                if (tags == null) continue;
                String name = tags.optString("name:zh", tags.optString("name", "")).trim();
                if (name.isEmpty()) continue;
                boolean duplicate = containsHotel(budget, name) || containsHotel(unclassified, name)
                    || containsHotel(upscale, name);
                if (duplicate) continue;
                String street = tags.optString("addr:street", "").trim();
                String stars = tags.optString("stars", "").trim();
                String tourism = tags.optString("tourism", "hotel");
                String detail = street.isEmpty() ? "附近住宿地点" : "地址标注：" + street;
                if (!stars.isEmpty()) detail += "；OSM 标注 " + stars + " 星";
                if (isBudgetHotel(name, tourism)) detail += "；平价住宿优先推荐";
                detail += "。价格、空房、评分和营业状态请向酒店或订房平台复核。";
                LocalData.Item item = new LocalData.Item(city.code + "-hotel-osm-" + element.optLong("id"), name,
                    detail, "OpenStreetMap · 平价住宿优先", 0xff536f78, "宿");
                if (isUpscaleHotel(name, stars)) upscale.add(item);
                else if (isBudgetHotel(name, tourism)) budget.add(item);
                else unclassified.add(item);
            }
        } catch (Exception ignored) { }
        List<LocalData.Item> result = new ArrayList<>();
        appendHotels(result, budget, 7);
        appendHotels(result, unclassified, 7);
        appendHotels(result, upscale, 7);
        return result;
    }

    private static boolean containsHotel(List<LocalData.Item> hotels, String name) {
        for (LocalData.Item item : hotels) if (item.title.equals(name)) return true;
        return false;
    }

    private static void appendHotels(List<LocalData.Item> target, List<LocalData.Item> source, int max) {
        for (LocalData.Item item : source) {
            if (target.size() >= max) return;
            target.add(item);
        }
    }

    private static boolean isBudgetHotel(String name, String tourism) {
        String value = name.toLowerCase();
        return "hostel".equals(tourism) || "guest_house".equals(tourism) || "motel".equals(tourism)
            || value.contains("青旅") || value.contains("青年旅舍") || value.contains("旅馆")
            || value.contains("旅店") || value.contains("客栈") || value.contains("民宿")
            || value.contains("快捷") || value.contains("经济型") || value.contains("招待所");
    }

    private static boolean isUpscaleHotel(String name, String stars) {
        try {
            String first = stars.replaceAll("[^0-9.].*$", "");
            if (!first.isEmpty() && Double.parseDouble(first) >= 3) return true;
        } catch (Exception ignored) { }
        String value = name.toLowerCase();
        return value.contains("五星") || value.contains("四星") || value.contains("三星")
            || value.contains("丽思卡尔顿") || value.contains("华尔道夫") || value.contains("四季酒店")
            || value.contains("瑞吉") || value.contains("柏悦") || value.contains("君悦")
            || value.contains("洲际") || value.contains("香格里拉") || value.contains("文华东方");
    }

    private static List<LocalData.Item> fetchOpenStreetMap(CityRepository.City city) {
        List<LocalData.Item> result = new ArrayList<>();
        try {
            String around = "(around:28000," + city.lat + "," + city.lon + ")";
            String query = "[out:json][timeout:14];("
                + "nwr" + around + "[tourism~\"attraction|museum|viewpoint|zoo|aquarium|theme_park\"][name];"
                + "nwr" + around + "[leisure~\"park|nature_reserve\"][name];"
                + "nwr" + around + "[historic~\"memorial|monument|battlefield\"][name];"
                + "nwr" + around + "[natural~\"peak|waterfall|cave_entrance|cliff\"][name];"
                + ");out tags center 60;";
            String endpoint = "https://overpass-api.de/api/interpreter?data="
                + URLEncoder.encode(query, StandardCharsets.UTF_8.name());
            HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", "XiangYuAndroid/1.1 (OpenStreetMap POI client)");
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            JSONArray elements = new JSONObject(body.toString()).optJSONArray("elements");
            if (elements == null) return result;
            for (int i = 0; i < elements.length() && result.size() < 12; i++) {
                JSONObject element = elements.getJSONObject(i);
                JSONObject tags = element.optJSONObject("tags");
                if (tags == null) continue;
                String name = tags.optString("name:zh", tags.optString("name", "")).trim();
                if (name.isEmpty()) continue;
                boolean duplicate = false;
                for (LocalData.Item item : result) if (item.title.equals(name)) duplicate = true;
                if (duplicate) continue;
                String label = scenicCategory(tags, name);
                JSONObject center = element.optJSONObject("center");
                double lat = element.has("lat") ? element.optDouble("lat") : center == null ? Double.NaN : center.optDouble("lat", Double.NaN);
                double lon = element.has("lon") ? element.optDouble("lon") : center == null ? Double.NaN : center.optDouble("lon", Double.NaN);
                String opening = tags.optString("opening_hours", "").trim();
                String fee = tags.optString("fee", "").trim();
                StringBuilder detail = new StringBuilder(scenicIntroduction(label));
                if (!opening.isEmpty()) detail.append(" 开放时间标注：").append(opening).append("。");
                if (!fee.isEmpty()) detail.append(" 收费标注：").append(fee).append("。");
                detail.append(" 实际开放、预约、门票和交通请在出发前通过官方渠道复核。");
                String mark = label.equals("亲子遛娃") ? "亲" : label.equals("红色学习") ? "红"
                    : label.equals("纯自然景观") ? "野" : "景";
                result.add(new LocalData.Item(city.code + "-osm-" + element.optLong("id"), name,
                    detail.toString(), label + " · OpenStreetMap · 市区约28公里范围", 0xff47748a, mark, lat, lon));
            }
        } catch (Exception ignored) { }
        return result;
    }

    private static String scenicCategory(JSONObject tags, String name) {
        String tourism = tags.optString("tourism", "");
        String historic = tags.optString("historic", "");
        String natural = tags.optString("natural", "");
        String leisure = tags.optString("leisure", "");
        String museum = tags.optString("museum", "");
        if (name.contains("纪念") || name.contains("革命") || name.contains("烈士")
                || name.contains("起义") || name.contains("会址") || name.contains("旧址")
                || historic.equals("memorial") || historic.equals("battlefield")) return "红色学习";
        if (tourism.equals("zoo") || tourism.equals("aquarium") || tourism.equals("theme_park")
                || museum.contains("science") || name.contains("科技馆") || name.contains("儿童")
                || name.contains("动物园") || name.contains("海洋馆")) return "亲子遛娃";
        if (!natural.isEmpty() || leisure.equals("nature_reserve") || name.contains("森林")
                || name.contains("湿地") || name.contains("峡谷") || name.contains("瀑布")
                || name.contains("草原") || name.contains("自然保护")) return "纯自然景观";
        return "人文与城市漫游";
    }

    private static String scenicIntroduction(String category) {
        if (category.equals("亲子遛娃")) {
            return "适合亲子半日游、自然观察或科普体验，建议重点核对儿童适龄范围、预约场次、休息区和推车通行条件。";
        }
        if (category.equals("红色学习")) {
            return "适合红色研学与城市历史学习，可结合基本陈列、旧址空间和定时讲解理解相关历史背景。";
        }
        if (category.equals("纯自然景观")) {
            return "以山林、湿地、地貌或生态环境为主要看点，适合轻徒步和自然观察，需关注天气、路况、补给与返程时间。";
        }
        return "适合了解当地历史、建筑、馆藏或城市公共空间，可安排一至三小时慢游并结合现场讲解。";
    }

    private static Result parse(CityRepository.City city, String extract, boolean fresh) {
        List<LocalData.Item> sights = new ArrayList<>();
        List<LocalData.Item> tips = new ArrayList<>();
        String section = "";
        int index = 0;
        for (String raw : extract.split("\\r?\\n")) {
            String line = raw.replace("=", "").trim();
            if (line.isEmpty()) continue;
            if (raw.startsWith("==")) {
                section = line;
                continue;
            }
            String compact = line.replaceAll("\\s+", " ");
            if (compact.length() < 18) continue;
            if (sights.size() < 3 && (section.contains("观光") || section.contains("景点") || section.contains("游览"))) {
                sights.add(item(city, "web-sight-" + index++, firstSentence(compact, 16), compact,
                    "维基导游 · 在线资料", 0xff47748a, "游"));
            }
            if (tips.size() < 4 && containsAny(compact, TIP_WORDS)) {
                tips.add(item(city, "web-tip-" + index++, firstSentence(compact, 16), compact,
                    "维基导游 · 7天内更新", 0xffa34c3a, "避"));
            }
        }
        return new Result(sights, tips, new ArrayList<>(), fresh);
    }

    private static LocalData.Item item(CityRepository.City city, String id, String title,
                                       String subtitle, String meta, int color, String mark) {
        return new LocalData.Item(city.code + "-" + id, title, truncate(subtitle, 68), meta, color, mark);
    }

    private static boolean containsAny(String text, String[] words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }

    private static String firstSentence(String text, int max) {
        int end = text.indexOf('。');
        String value = end > 2 ? text.substring(0, end) : text;
        return truncate(value, max);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private DestinationService() {}
}
