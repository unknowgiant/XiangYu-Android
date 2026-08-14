package cn.xiangyu.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FoodShopService {
    interface Callback { void onResult(Result result); }

    static final class Result {
        final List<String> shops;
        final String baiduMapUrl;
        final String amapUrl;
        final String meituanUrl;
        final String xiaohongshuUrl;

        Result(List<String> shops, String baiduMapUrl, String amapUrl,
               String meituanUrl, String xiaohongshuUrl) {
            this.shops = shops;
            this.baiduMapUrl = baiduMapUrl;
            this.amapUrl = amapUrl;
            this.meituanUrl = meituanUrl;
            this.xiaohongshuUrl = xiaohongshuUrl;
        }
    }

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Map<String, Result> CACHE = new LinkedHashMap<>();
    private static final Pattern BAIDU_TITLE = Pattern.compile(
        "<h3[^>]*>.*?<a[^>]*>(.*?)</a>.*?</h3>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern BING_TITLE = Pattern.compile(
        "<li[^>]+class=[\"'][^\"']*b_algo[^\"']*[\"'][^>]*>.*?<h2>.*?<a[^>]*>(.*?)</a>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern JSON_TITLE = Pattern.compile(
        "[\"'](?:title|titleContent)[\"']\\s*:\\s*[\"'](.*?)[\"']",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    static void fetch(CityRepository.City cityValue, String food, Callback callback) {
        String city = cityValue.name;
        String query = city + " " + food + " 推荐 店 老字号";
        String key = city + ":" + food;
        synchronized (CACHE) {
            Result cached = CACHE.get(key);
            if (cached != null) { callback.onResult(cached); return; }
        }
        EXECUTOR.execute(() -> {
            Set<String> candidates = new LinkedHashSet<>();
            String meituanQuery = city + " " + food + " 美团 必吃 店名";
            String xiaohongshuQuery = city + " " + food + " 小红书 探店 店名";
            try {
                String html = request("https://www.baidu.com/s?ie=utf-8&wd=" + encode(meituanQuery));
                collect(html, BAIDU_TITLE, city, food, candidates);
                collect(html, JSON_TITLE, city, food, candidates);
            }
            catch (Exception ignored) { }
            if (candidates.size() < 5) {
                try {
                    String html = request("https://www.baidu.com/s?ie=utf-8&wd=" + encode(xiaohongshuQuery));
                    collect(html, BAIDU_TITLE, city, food, candidates);
                    collect(html, JSON_TITLE, city, food, candidates);
                } catch (Exception ignored) { }
            }
            if (candidates.size() < 5) {
                try { collect(request("https://cn.bing.com/search?q=" + encode(query + " 美团 小红书")), BING_TITLE, city, food, candidates); }
                catch (Exception ignored) { }
            }
            if (candidates.size() < 5) collectOpenStreetMap(cityValue, food, candidates);
            List<String> shops = new ArrayList<>();
            for (String candidate : candidates) {
                shops.add(candidate);
                if (shops.size() == 5) break;
            }
            String mapQuery = city + " " + food;
            Result result = new Result(shops,
                "https://map.baidu.com/search/" + encode(mapQuery),
                "https://uri.amap.com/search?keyword=" + encode(mapQuery) + "&city=" + encode(city),
                "https://www.baidu.com/s?wd=" + encode(meituanQuery + " site:meituan.com"),
                "https://www.xiaohongshu.com/search_result?keyword=" + encode(city + " " + food + " 探店"));
            synchronized (CACHE) {
                if (CACHE.size() >= 60) CACHE.remove(CACHE.keySet().iterator().next());
                CACHE.put(key, result);
            }
            callback.onResult(result);
        });
    }

    private static void collect(String html, Pattern pattern, String city, String food,
                                Set<String> result) {
        Matcher matcher = pattern.matcher(html);
        while (matcher.find() && result.size() < 10) {
            String title = clean(matcher.group(1));
            if (!title.contains(city)) continue;
            title = title.replaceAll("\\s*[-_—|]\\s*(?:\\(|（)?.*$", "")
                .replaceAll("^(推荐|探店)[：:]?", "").trim();
            if (title.length() < 2 || title.length() > 34) continue;
            String foodKey = food.replace(city, "").replace("代表小吃线索", "").trim();
            if ((!foodKey.isEmpty() && !title.contains(foodKey)) && !looksLikeShop(title)) continue;
            if (title.contains("大家还在搜") || title.contains("店名字") || title.contains("上榜理由")
                    || title.contains("攻略") || title.contains("排行榜") || title.contains("哪家")
                    || title.contains("十大") || title.contains("前十") || title.contains("盘点")
                    || title.contains("知乎") || title.contains("百度百科")) continue;
            title = title.replaceAll("^[（(]?" + Pattern.quote(city) + "[）)]?", "").trim();
            if (!title.isEmpty()) result.add(title);
        }
    }

    private static void collectOpenStreetMap(CityRepository.City city, String food, Set<String> result) {
        try {
            String around = "(around:22000," + city.lat + "," + city.lon + ")";
            String query = "[out:json][timeout:12];(nwr" + around
                + "[amenity~\"restaurant|fast_food|food_court\"][name];);out tags center 80;";
            String endpoint = "https://overpass-api.de/api/interpreter?data=" + encode(query);
            JSONArray elements = new JSONObject(request(endpoint)).optJSONArray("elements");
            if (elements == null) return;
            List<String> matches = new ArrayList<>();
            List<String> others = new ArrayList<>();
            String foodKey = food.replace(city.name, "").replace("代表小吃线索", "").trim();
            for (int i = 0; i < elements.length(); i++) {
                JSONObject tags = elements.optJSONObject(i) == null ? null
                    : elements.optJSONObject(i).optJSONObject("tags");
                if (tags == null) continue;
                String name = tags.optString("name:zh", tags.optString("name", "")).trim();
                if (name.length() < 2 || name.length() > 32) continue;
                String cuisine = tags.optString("cuisine", "");
                if ((!foodKey.isEmpty() && (name.contains(foodKey) || cuisine.contains(foodKey)))
                        || looksLikeShop(name)) matches.add(name);
                else others.add(name);
            }
            for (String name : matches) {
                result.add(name);
                if (result.size() >= 5) return;
            }
            for (String name : others) {
                result.add(name);
                if (result.size() >= 5) return;
            }
        } catch (Exception ignored) { }
    }

    private static boolean looksLikeShop(String value) {
        return value.contains("店") || value.contains("馆") || value.contains("楼")
            || value.contains("餐厅") || value.contains("老号") || value.contains("老字号")
            || value.contains("食府") || value.contains("小吃") || value.contains("记");
    }

    private static String request(String endpoint) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(4000); connection.setReadTimeout(6000);
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/132 Safari/537.36");
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line; int total = 0;
            while ((line = reader.readLine()) != null && total < 3_200_000) {
                body.append(line); total += line.length();
            }
        } finally { connection.disconnect(); }
        return body.toString();
    }

    private static String clean(String value) {
        return value.replaceAll("<[^>]+>", " ").replace("&quot;", "\"")
            .replace("&amp;", "&").replace("&#39;", "'").replace("&nbsp;", " ")
            .replaceAll("&#x?[0-9a-fA-F]+;", " ").replaceAll("\\s+", " ").trim();
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, StandardCharsets.UTF_8.name()); }
        catch (Exception ignored) { return value; }
    }

    private FoodShopService() { }
}
