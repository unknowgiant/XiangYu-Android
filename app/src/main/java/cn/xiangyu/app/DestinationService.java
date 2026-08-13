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
            if ((!cached.isEmpty() || !cachedOsm.isEmpty()) && cacheVersion >= 5
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
                    if (!osmSights.isEmpty()) cache.edit().putString(key + "_osm", serializeOsm(osmSights)).putInt(key + "_version", 5).apply();
                    if (!hotels.isEmpty()) cache.edit().putString(key + "_hotels", serializeOsm(hotels)).apply();
                    callback.onResult(new Result(osmSights.isEmpty() ? wiki.sights : osmSights, wiki.tips, hotels, true));
                    return;
                }
                List<LocalData.Item> osmSights = fetchOpenStreetMap(city);
                if (!osmSights.isEmpty()) {
                    cache.edit().putString(key + "_osm", serializeOsm(osmSights))
                        .putInt(key + "_version", 5).putLong(key + "_time", System.currentTimeMillis()).apply();
                    List<LocalData.Item> hotels = fetchOpenStreetMapHotels(city);
                    if (!hotels.isEmpty()) cache.edit().putString(key + "_hotels", serializeOsm(hotels)).apply();
                    callback.onResult(new Result(osmSights, new ArrayList<>(), hotels, true));
                    return;
                }
            } catch (Exception ignored) { }
            List<LocalData.Item> osmSights = fetchOpenStreetMap(city);
            if (!osmSights.isEmpty()) {
                cache.edit().putString(key + "_osm", serializeOsm(osmSights))
                    .putInt(key + "_version", 5).putLong(key + "_time", System.currentTimeMillis()).apply();
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
            String query = "[out:json][timeout:10];(node(around:18000," + city.lat + "," + city.lon
                + ")[tourism~\"attraction|museum|viewpoint\"][name];way(around:18000," + city.lat + "," + city.lon
                + ")[tourism~\"attraction|museum|viewpoint\"][name];node(around:12000," + city.lat + "," + city.lon
                + ")[leisure=park][name];);out tags center 30;";
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
            for (int i = 0; i < elements.length() && result.size() < 7; i++) {
                JSONObject element = elements.getJSONObject(i);
                JSONObject tags = element.optJSONObject("tags");
                if (tags == null) continue;
                String name = tags.optString("name:zh", tags.optString("name", "")).trim();
                if (name.isEmpty()) continue;
                boolean duplicate = false;
                for (LocalData.Item item : result) if (item.title.equals(name)) duplicate = true;
                if (duplicate) continue;
                String kind = tags.optString("tourism", "park");
                String label = kind.equals("museum") ? "博物馆" : kind.equals("viewpoint") ? "观景点" : kind.equals("park") ? "公园" : "景点";
                JSONObject center = element.optJSONObject("center");
                double lat = element.has("lat") ? element.optDouble("lat") : center == null ? Double.NaN : center.optDouble("lat", Double.NaN);
                double lon = element.has("lon") ? element.optDouble("lon") : center == null ? Double.NaN : center.optDouble("lon", Double.NaN);
                result.add(new LocalData.Item(city.code + "-osm-" + element.optLong("id"), name,
                    "来自开放地图的" + label + "，开放状态、门票和路线请在出发前复核。",
                    "OpenStreetMap · 附近约18公里", 0xff47748a, "图", lat, lon));
            }
        } catch (Exception ignored) { }
        return result;
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
