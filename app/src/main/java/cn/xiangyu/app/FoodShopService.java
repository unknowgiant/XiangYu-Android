package cn.xiangyu.app;

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

        Result(List<String> shops, String baiduMapUrl, String amapUrl) {
            this.shops = shops;
            this.baiduMapUrl = baiduMapUrl;
            this.amapUrl = amapUrl;
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

    static void fetch(String city, String food, Callback callback) {
        String query = city + " " + food + " 推荐 店 老字号";
        String key = city + ":" + food;
        synchronized (CACHE) {
            Result cached = CACHE.get(key);
            if (cached != null) { callback.onResult(cached); return; }
        }
        EXECUTOR.execute(() -> {
            Set<String> candidates = new LinkedHashSet<>();
            try {
                String html = request("https://www.baidu.com/s?ie=utf-8&wd=" + encode(query));
                collect(html, BAIDU_TITLE, city, food, candidates);
                collect(html, JSON_TITLE, city, food, candidates);
            }
            catch (Exception ignored) { }
            if (candidates.size() < 2) {
                try { collect(request("https://cn.bing.com/search?q=" + encode(query)), BING_TITLE, city, food, candidates); }
                catch (Exception ignored) { }
            }
            List<String> shops = new ArrayList<>();
            for (String candidate : candidates) {
                shops.add(candidate);
                if (shops.size() == 2) break;
            }
            String mapQuery = city + " " + food;
            Result result = new Result(shops,
                "https://map.baidu.com/search/" + encode(mapQuery),
                "https://uri.amap.com/search?keyword=" + encode(mapQuery) + "&city=" + encode(city));
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
        while (matcher.find() && result.size() < 4) {
            String title = clean(matcher.group(1));
            title = title.replaceAll("\\s*[-_—|]\\s*(?:\\(|（)?.*$", "")
                .replaceAll("^(推荐|探店)[：:]?", "").trim();
            if (title.length() < 2 || title.length() > 34) continue;
            String foodKey = food.replace(city, "").replace("代表小吃线索", "").trim();
            if ((!foodKey.isEmpty() && !title.contains(foodKey)) && !looksLikeShop(title)) continue;
            if (title.contains("攻略") || title.contains("排行榜") || title.contains("哪家")
                    || title.contains("知乎") || title.contains("百度百科")) continue;
            title = title.replaceAll("^[（(]?" + Pattern.quote(city) + "[）)]?", "").trim();
            if (!title.isEmpty()) result.add(title);
        }
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
