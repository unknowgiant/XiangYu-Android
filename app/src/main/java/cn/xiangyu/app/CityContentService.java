package cn.xiangyu.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fetches city-scoped food and culture results that are not supplied by OSM. */
final class CityContentService {
    interface Callback { void onResult(Result result); }

    static final class Result {
        final List<LocalData.Item> food;
        final List<LocalData.Item> culture;
        final boolean fresh;

        Result(List<LocalData.Item> food, List<LocalData.Item> culture, boolean fresh) {
            this.food = food;
            this.culture = culture;
            this.fresh = fresh;
        }
    }

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final long CACHE_LIFE = 7L * 24 * 60 * 60 * 1000;
    private static final int CACHE_VERSION = 1;
    private static final Pattern BING_TITLE = Pattern.compile(
        "<li[^>]+class=\\\"b_algo\\\"[^>]*>.*?<h2[^>]*>\\s*<a[^>]*>(.*?)</a>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern BAIDU_TITLE = Pattern.compile(
        "<h3[^>]*>\\s*<a[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    static void fetch(Context context, CityRepository.City city, Callback callback) {
        EXECUTOR.execute(() -> {
            SharedPreferences cache = context.getSharedPreferences("city_content_cache", Context.MODE_PRIVATE);
            String prefix = "v" + CACHE_VERSION + "_" + city.code;
            long time = cache.getLong(prefix + "_time", 0);
            String cached = cache.getString(prefix + "_json", "");
            if (!cached.isEmpty() && System.currentTimeMillis() - time < CACHE_LIFE) {
                callback.onResult(parseCache(city, cached, false));
                return;
            }

            List<LocalData.Item> food = fetchCategory(city, 0,
                city.officialName + " 特色小吃 美食 老字号");
            List<LocalData.Item> culture = fetchCategory(city, 1,
                city.officialName + " 民俗 非遗 传统文化");
            Result result = new Result(food, culture, !food.isEmpty() || !culture.isEmpty());
            if (result.fresh) {
                cache.edit().putString(prefix + "_json", serialize(result))
                    .putLong(prefix + "_time", System.currentTimeMillis()).apply();
            } else if (!cached.isEmpty()) {
                result = parseCache(city, cached, false);
            }
            callback.onResult(result);
        });
    }

    private static List<LocalData.Item> fetchCategory(CityRepository.City city, int category, String query) {
        List<LocalData.Item> places = fetchOpenStreetMap(city, category);
        if (!places.isEmpty()) return places;
        List<String> titles = new ArrayList<>();
        try {
            String endpoint = "https://cn.bing.com/search?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8.name()) + "&ensearch=0";
            titles.addAll(extractTitles(request(endpoint), BING_TITLE));
        } catch (Exception ignored) { }
        if (titles.size() < 3) {
            try {
                String endpoint = "https://www.baidu.com/s?wd="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8.name());
                titles.addAll(extractTitles(request(endpoint), BAIDU_TITLE));
            } catch (Exception ignored) { }
        }

        List<LocalData.Item> result = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (String raw : titles) {
            if (!raw.contains(city.name) && !raw.contains(city.officialName)) continue;
            String title = normalizeTitle(raw);
            if (title.length() < 2 || title.length() > 32 || !used.add(title)) continue;
            String type = category == 0 ? "小吃与美食" : "民俗与非遗";
            String subtitle = "按“" + city.officialName + "”检索到的" + type
                + "公开资料，点开可继续通过百度百科、百度或必应中国核实具体内容。";
            result.add(new LocalData.Item(city.code + "-online-" + category + "-" + result.size(),
                title, subtitle, "地市代码 " + city.code + " · 互联网更新", category == 0 ? 0xffc4633f : 0xff537263,
                category == 0 ? "食" : "俗"));
            if (result.size() >= 5) break;
        }
        return result;
    }

    private static List<LocalData.Item> fetchOpenStreetMap(CityRepository.City city, int category) {
        List<LocalData.Item> result = new ArrayList<>();
        try {
            String selector = category == 0
                ? "[amenity~\"restaurant|fast_food|cafe|food_court\"][name]"
                : "[tourism=museum][name];node(around:22000," + city.lat + "," + city.lon
                    + ")[amenity~\"arts_centre|theatre\"][name];node(around:22000," + city.lat + "," + city.lon
                    + ")[historic][name]";
            String query;
            if (category == 0) {
                query = "[out:json][timeout:12];(node(around:22000," + city.lat + "," + city.lon + ")" + selector
                    + ";way(around:22000," + city.lat + "," + city.lon + ")" + selector + ";);out tags center 35;";
            } else {
                query = "[out:json][timeout:12];(node(around:22000," + city.lat + "," + city.lon + ")"
                    + selector + ";way(around:22000," + city.lat + "," + city.lon
                    + ")[tourism=museum][name];way(around:22000," + city.lat + "," + city.lon
                    + ")[amenity~\"arts_centre|theatre\"][name];way(around:22000," + city.lat + "," + city.lon
                    + ")[historic][name];);out tags center 35;";
            }
            String endpoint = "https://overpass-api.de/api/interpreter?data="
                + URLEncoder.encode(query, StandardCharsets.UTF_8.name());
            JSONObject root = new JSONObject(request(endpoint));
            JSONArray elements = root.optJSONArray("elements");
            if (elements == null) return result;
            Set<String> used = new HashSet<>();
            for (int i = 0; i < elements.length() && result.size() < 5; i++) {
                JSONObject element = elements.optJSONObject(i);
                JSONObject tags = element == null ? null : element.optJSONObject("tags");
                if (tags == null) continue;
                String name = tags.optString("name:zh", tags.optString("name", "")).trim();
                if (name.length() < 2 || !used.add(name)) continue;
                JSONObject center = element.optJSONObject("center");
                double lat = element.has("lat") ? element.optDouble("lat")
                    : center == null ? Double.NaN : center.optDouble("lat", Double.NaN);
                double lon = element.has("lon") ? element.optDouble("lon")
                    : center == null ? Double.NaN : center.optDouble("lon", Double.NaN);
                String detail;
                String mark;
                int color;
                if (category == 0) {
                    String cuisine = tags.optString("cuisine", "").replace(';', '、');
                    detail = cuisine.isEmpty() ? "本市坐标范围内的餐饮地点"
                        : "开放地图标注菜系：" + cuisine;
                    detail += "。是否属于当地特色、价格和营业状态请结合近期评价核实。";
                    mark = "食";
                    color = 0xffc4633f;
                } else {
                    String kind = tags.has("historic") ? "历史文化地点"
                        : "museum".equals(tags.optString("tourism")) ? "博物馆"
                        : "theatre".equals(tags.optString("amenity")) ? "剧院" : "文化艺术场所";
                    detail = "本市坐标范围内的" + kind + "。开放时间、预约、活动和拍摄规则请提前核实。";
                    mark = "文";
                    color = 0xff537263;
                }
                result.add(new LocalData.Item(city.code + "-osm-content-" + category + "-"
                    + element.optLong("id"), name, detail,
                    "地市代码 " + city.code + " · OpenStreetMap", color, mark, lat, lon));
            }
        } catch (Exception ignored) { }
        return result;
    }

    private static String request(String endpoint) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(7000);
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        connection.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/132 Mobile Safari/537.36");
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && body.length() < 1_500_000) body.append(line);
        } finally {
            connection.disconnect();
        }
        return body.toString();
    }

    private static List<String> extractTitles(String html, Pattern pattern) {
        List<String> result = new ArrayList<>();
        Matcher matcher = pattern.matcher(html);
        while (matcher.find() && result.size() < 10) result.add(cleanHtml(matcher.group(1)));
        return result;
    }

    private static String normalizeTitle(String value) {
        String result = cleanHtml(value).replaceAll("(?i)[-_\\s|]+(百度百科|百度知道|知乎|搜狐|腾讯网|新浪网).*$", "")
            .replaceAll("^(最新|盘点|推荐|攻略)[：:]?", "").trim();
        return result.length() <= 32 ? result : result.substring(0, 32) + "…";
    }

    private static String cleanHtml(String value) {
        return value.replaceAll("<[^>]+>", " ").replace("&quot;", "\"")
            .replace("&amp;", "&").replace("&#39;", "'").replace("&nbsp;", " ")
            .replaceAll("&#x?[0-9a-fA-F]+;", " ").replaceAll("\\s+", " ").trim();
    }

    private static String serialize(Result result) {
        JSONObject root = new JSONObject();
        try {
            root.put("food", serializeItems(result.food));
            root.put("culture", serializeItems(result.culture));
        } catch (Exception ignored) { }
        return root.toString();
    }

    private static JSONArray serializeItems(List<LocalData.Item> items) throws Exception {
        JSONArray array = new JSONArray();
        for (LocalData.Item item : items) {
            JSONObject value = new JSONObject();
            value.put("title", item.title);
            value.put("subtitle", item.subtitle);
            array.put(value);
        }
        return array;
    }

    private static Result parseCache(CityRepository.City city, String json, boolean fresh) {
        try {
            JSONObject root = new JSONObject(json);
            return new Result(parseItems(city, root.optJSONArray("food"), 0),
                parseItems(city, root.optJSONArray("culture"), 1), fresh);
        } catch (Exception ignored) {
            return new Result(new ArrayList<>(), new ArrayList<>(), false);
        }
    }

    private static List<LocalData.Item> parseItems(CityRepository.City city, JSONArray array, int category) {
        List<LocalData.Item> result = new ArrayList<>();
        if (array == null) return result;
        for (int i = 0; i < array.length(); i++) {
            JSONObject value = array.optJSONObject(i);
            if (value == null) continue;
            result.add(new LocalData.Item(city.code + "-online-" + category + "-" + i,
                value.optString("title"), value.optString("subtitle"),
                "地市代码 " + city.code + " · 7天缓存", category == 0 ? 0xffc4633f : 0xff537263,
                category == 0 ? "食" : "俗"));
        }
        return result;
    }

    private CityContentService() { }
}
