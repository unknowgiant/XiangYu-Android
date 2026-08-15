package cn.xiangyu.app;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Uses official Amap POI first, with public platform pages only as fallback links. */
final class FoodShopService {
    interface Callback { void onResult(Result result); }

    static final class Result {
        final List<String> shops;
        final Map<String, String> sources;
        final String amapUrl;
        final String meituanUrl;
        final String douyinUrl;
        final String xiaohongshuUrl;

        Result(List<String> shops, Map<String, String> sources, String amapUrl,
               String meituanUrl, String douyinUrl, String xiaohongshuUrl) {
            this.shops = shops;
            this.sources = sources;
            this.amapUrl = amapUrl;
            this.meituanUrl = meituanUrl;
            this.douyinUrl = douyinUrl;
            this.xiaohongshuUrl = xiaohongshuUrl;
        }

        String sourceOf(String shop) { return sources.getOrDefault(shop, "公开平台"); }
    }

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Map<String, Result> CACHE = new LinkedHashMap<>();
    private static final Pattern PLATFORM_NAME = Pattern.compile(
        "[\\\"'](?:poiName|shopName|merchantName|displayTitle|title)[\\\"']\\s*:\\s*[\\\"']([^\\\"']{2,40})[\\\"']",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");

    static void fetch(CityRepository.City cityValue, String food, Callback callback) {
        String city = cityValue.name;
        String key = city + ":" + food;
        synchronized (CACHE) {
            Result cached = CACHE.get(key);
            if (cached != null) { callback.onResult(cached); return; }
        }
        EXECUTOR.execute(() -> {
            String meituan = meituanSearchUrl(city, food);
            String douyin = douyinSearchUrl(city, food);
            String xiaohongshu = xiaohongshuSearchUrl(city, food);
            String amap = AmapPoiService.searchUrl(city, food);
            Map<String, String> candidates = new LinkedHashMap<>();
            for (AmapPoiService.Poi poi : AmapPoiService.search(
                    cityValue, food, AmapPoiService.DINING_TYPES, 10)) {
                candidates.putIfAbsent(poi.detail(false), "高德开放平台 POI");
                if (candidates.size() >= 5) break;
            }
            if (candidates.size() < 5) collectPlatform(meituan, "美团补充", candidates);
            if (candidates.size() < 5) collectPlatform(douyin, "抖音补充", candidates);
            if (candidates.size() < 5) collectPlatform(xiaohongshu, "小红书补充", candidates);
            List<String> shops = new ArrayList<>();
            Map<String, String> sources = new LinkedHashMap<>();
            for (Map.Entry<String, String> candidate : candidates.entrySet()) {
                shops.add(candidate.getKey());
                sources.put(candidate.getKey(), candidate.getValue());
                if (shops.size() >= 5) break;
            }
            Result result = new Result(shops, sources, amap, meituan, douyin, xiaohongshu);
            synchronized (CACHE) {
                if (CACHE.size() >= 60) CACHE.remove(CACHE.keySet().iterator().next());
                CACHE.put(key, result);
            }
            callback.onResult(result);
        });
    }

    private static void collectPlatform(String endpoint, String source, Map<String, String> result) {
        try {
            Matcher matcher = PLATFORM_NAME.matcher(request(endpoint));
            while (matcher.find() && result.size() < 12) {
                String title = clean(matcher.group(1));
                if (!looksLikeShop(title) || !ContentSafety.isSafeTitle(title)
                        || looksLikeEditorial(title)) continue;
                result.putIfAbsent(title, source);
            }
        } catch (Exception ignored) { }
    }

    private static boolean looksLikeShop(String value) {
        return value.contains("店") || value.contains("馆") || value.contains("楼")
            || value.contains("餐厅") || value.contains("老号") || value.contains("老字号")
            || value.contains("食府") || value.contains("小吃") || value.contains("记");
    }

    private static boolean looksLikeEditorial(String value) {
        return value.contains("搜索") || value.contains("登录") || value.contains("攻略")
            || value.contains("排行榜") || value.contains("推荐") || value.contains("探店")
            || value.contains("合集") || value.contains("盘点") || value.contains("测评")
            || value.contains("必吃") || value.contains("打卡") || value.contains("这家")
            || value.contains("分享");
    }

    static String meituanSearchUrl(String city, String food) {
        return "https://www.meituan.com/s/" + encode(city + " " + food).replace("+", "%20") + "/";
    }

    static String xiaohongshuSearchUrl(String city, String food) {
        return "https://www.xiaohongshu.com/search_result?keyword=" + encode(city + " " + food + " 探店");
    }

    static String douyinSearchUrl(String city, String food) {
        return "https://www.douyin.com/search/"
            + encode(city + " " + food + " 探店").replace("+", "%20");
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
        return decodeUnicode(value).replace("\\u002F", "/").replace("\\\"", "\"")
            .replaceAll("<[^>]+>", " ").replace("&quot;", "\"").replace("&amp;", "&")
            .replace("&#39;", "'").replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
    }

    private static String decodeUnicode(String value) {
        Matcher matcher = UNICODE_ESCAPE.matcher(value);
        StringBuffer decoded = new StringBuffer();
        while (matcher.find()) {
            char character = (char) Integer.parseInt(matcher.group(1), 16);
            matcher.appendReplacement(decoded, Matcher.quoteReplacement(String.valueOf(character)));
        }
        matcher.appendTail(decoded);
        return decoded.toString();
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, StandardCharsets.UTF_8.name()); }
        catch (Exception ignored) { return value; }
    }

    private FoodShopService() { }
}
