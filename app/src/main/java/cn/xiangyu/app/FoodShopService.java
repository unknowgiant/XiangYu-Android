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

/** Reads only public Meituan and Xiaohongshu pages; no account or private API is used. */
final class FoodShopService {
    interface Callback { void onResult(Result result); }

    static final class Result {
        final List<String> shops;
        final String meituanUrl;
        final String xiaohongshuUrl;

        Result(List<String> shops, String meituanUrl, String xiaohongshuUrl) {
            this.shops = shops;
            this.meituanUrl = meituanUrl;
            this.xiaohongshuUrl = xiaohongshuUrl;
        }
    }

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Map<String, Result> CACHE = new LinkedHashMap<>();
    private static final Pattern PLATFORM_NAME = Pattern.compile(
        "[\\\"'](?:poiName|shopName|merchantName|displayTitle|title)[\\\"']\\s*:\\s*[\\\"']([^\\\"']{2,40})[\\\"']",
        Pattern.CASE_INSENSITIVE);

    static void fetch(CityRepository.City cityValue, String food, Callback callback) {
        String city = cityValue.name;
        String key = city + ":" + food;
        synchronized (CACHE) {
            Result cached = CACHE.get(key);
            if (cached != null) { callback.onResult(cached); return; }
        }
        EXECUTOR.execute(() -> {
            String meituan = meituanSearchUrl(city, food);
            String xiaohongshu = xiaohongshuSearchUrl(city, food);
            Set<String> candidates = new LinkedHashSet<>();
            collectPlatform(meituan, city, food, candidates);
            if (candidates.size() < 3) collectPlatform(xiaohongshu, city, food, candidates);
            List<String> shops = new ArrayList<>();
            for (String candidate : candidates) {
                shops.add(candidate);
                if (shops.size() >= 3) break;
            }
            Result result = new Result(shops, meituan, xiaohongshu);
            synchronized (CACHE) {
                if (CACHE.size() >= 60) CACHE.remove(CACHE.keySet().iterator().next());
                CACHE.put(key, result);
            }
            callback.onResult(result);
        });
    }

    private static void collectPlatform(String endpoint, String city, String food, Set<String> result) {
        try {
            Matcher matcher = PLATFORM_NAME.matcher(request(endpoint));
            while (matcher.find() && result.size() < 6) {
                String title = clean(matcher.group(1));
                if (!looksLikeShop(title)) continue;
                if (title.contains("搜索") || title.contains("攻略") || title.contains("排行榜")
                        || title.contains("推荐") || title.contains("登录")) continue;
                result.add(title);
            }
        } catch (Exception ignored) { }
    }

    private static boolean looksLikeShop(String value) {
        return value.contains("店") || value.contains("馆") || value.contains("楼")
            || value.contains("餐厅") || value.contains("老号") || value.contains("老字号")
            || value.contains("食府") || value.contains("小吃") || value.contains("记");
    }

    static String meituanSearchUrl(String city, String food) {
        return "https://www.meituan.com/s/" + encode(city + " " + food).replace("+", "%20") + "/";
    }

    static String xiaohongshuSearchUrl(String city, String food) {
        return "https://www.xiaohongshu.com/search_result?keyword=" + encode(city + " " + food + " 探店");
    }

    private static String request(String endpoint) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(4500);
        connection.setReadTimeout(7000);
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        connection.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/132 Mobile Safari/537.36");
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && body.length() < 3_000_000) body.append(line);
        } finally {
            connection.disconnect();
        }
        return body.toString();
    }

    private static String clean(String value) {
        return value.replace("\\u002F", "/").replace("\\\"", "\"")
            .replaceAll("<[^>]+>", " ").replace("&quot;", "\"").replace("&amp;", "&")
            .replace("&#39;", "'").replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, StandardCharsets.UTF_8.name()); }
        catch (Exception ignored) { return value; }
    }

    private FoodShopService() { }
}
