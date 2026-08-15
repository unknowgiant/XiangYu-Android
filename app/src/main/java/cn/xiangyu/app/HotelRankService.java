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

/** Reads public Meituan and Xiaohongshu lodging pages without a platform account. */
final class HotelRankService {
    interface Callback { void onResult(Result result); }

    static final class Result {
        final List<String> hotels;
        final Map<String, String> sources;
        final String meituanUrl;
        final String xiaohongshuUrl;

        Result(List<String> hotels, Map<String, String> sources,
               String meituanUrl, String xiaohongshuUrl) {
            this.hotels = hotels;
            this.sources = sources;
            this.meituanUrl = meituanUrl;
            this.xiaohongshuUrl = xiaohongshuUrl;
        }

        String sourceOf(String hotel) { return sources.getOrDefault(hotel, "公开平台"); }
    }

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Map<String, Result> CACHE = new LinkedHashMap<>();
    private static final Pattern PLATFORM_NAME = Pattern.compile(
        "[\\\"'](?:poiName|shopName|hotelName|merchantName|displayTitle|title)[\\\"']\\s*:\\s*[\\\"']([^\\\"']{3,45})[\\\"']",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");

    static void fetch(CityRepository.City city, Callback callback) {
        synchronized (CACHE) {
            Result cached = CACHE.get(city.code);
            if (cached != null) { callback.onResult(cached); return; }
        }
        EXECUTOR.execute(() -> {
            String meituan = meituanSearchUrl(city.name);
            String xiaohongshu = xiaohongshuSearchUrl(city.name);
            Map<String, String> candidates = new LinkedHashMap<>();
            collect(meituan, "美团", candidates);
            if (candidates.size() < 5) collect(xiaohongshu, "小红书补充", candidates);
            List<String> hotels = new ArrayList<>();
            Map<String, String> sources = new LinkedHashMap<>();
            for (Map.Entry<String, String> candidate : candidates.entrySet()) {
                if (isUpscaleHotel(candidate.getKey())) continue;
                hotels.add(candidate.getKey());
                sources.put(candidate.getKey(), candidate.getValue());
                if (hotels.size() >= 5) break;
            }
            Result result = new Result(hotels, sources, meituan, xiaohongshu);
            synchronized (CACHE) {
                if (CACHE.size() >= 40) CACHE.remove(CACHE.keySet().iterator().next());
                CACHE.put(city.code, result);
            }
            callback.onResult(result);
        });
    }

    private static void collect(String endpoint, String source, Map<String, String> result) {
        try {
            Matcher matcher = PLATFORM_NAME.matcher(request(endpoint));
            while (matcher.find() && result.size() < 20) {
                String title = clean(matcher.group(1));
                if (!looksLikeHotel(title) || isUnsafe(title) || looksLikeEditorial(title)) continue;
                result.putIfAbsent(title, source);
            }
        } catch (Exception ignored) { }
    }

    private static boolean looksLikeHotel(String value) {
        return value.contains("酒店") || value.contains("宾馆") || value.contains("客栈")
            || value.contains("民宿") || value.contains("旅馆") || value.contains("旅店")
            || value.contains("青旅") || value.contains("青年旅舍");
    }

    private static boolean isUpscaleHotel(String value) {
        return value.contains("五星") || value.contains("四星") || value.contains("三星")
            || value.contains("丽思卡尔顿") || value.contains("华尔道夫") || value.contains("四季酒店")
            || value.contains("瑞吉") || value.contains("柏悦") || value.contains("君悦")
            || value.contains("洲际") || value.contains("香格里拉") || value.contains("文华东方");
    }

    private static boolean isUnsafe(String value) {
        return value.contains("成人") || value.contains("色情") || value.contains("赌博")
            || value.contains("贷款") || value.contains("招嫖") || value.contains("约炮")
            || value.contains("验证码") || value.contains("短信转发");
    }

    private static boolean looksLikeEditorial(String value) {
        return value.contains("搜索") || value.contains("登录") || value.contains("攻略")
            || value.contains("排行榜") || value.contains("推荐") || value.contains("探店")
            || value.contains("合集") || value.contains("盘点") || value.contains("测评")
            || value.contains("入住体验") || value.contains("避雷") || value.contains("这家")
            || value.contains("分享");
    }

    static String meituanSearchUrl(String city) {
        return "https://www.meituan.com/s/" + encode(city + " 平价酒店 住宿").replace("+", "%20") + "/";
    }

    static String xiaohongshuSearchUrl(String city) {
        return "https://www.xiaohongshu.com/search_result?keyword=" + encode(city + " 平价住宿 酒店");
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
        return decodeUnicode(value).replace("\\\"", "\"").replaceAll("<[^>]+>", " ")
            .replace("&quot;", "\"").replace("&amp;", "&").replace("&#39;", "'")
            .replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
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

    private HotelRankService() { }
}
