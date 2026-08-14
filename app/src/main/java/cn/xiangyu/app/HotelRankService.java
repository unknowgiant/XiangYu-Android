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

/** Reads public search indexes for current platform lodging lists; no platform account or private API is used. */
final class HotelRankService {
    interface Callback { void onResult(Result result); }

    static final class Result {
        final List<String> hotels;
        final String meituanUrl;
        final String xiaohongshuUrl;

        Result(List<String> hotels, String meituanUrl, String xiaohongshuUrl) {
            this.hotels = hotels;
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

    static void fetch(CityRepository.City city, Callback callback) {
        synchronized (CACHE) {
            Result cached = CACHE.get(city.code);
            if (cached != null) { callback.onResult(cached); return; }
        }
        EXECUTOR.execute(() -> {
            Set<String> candidates = new LinkedHashSet<>();
            String meituanQuery = city.name + " 平价酒店 美团 人气榜 住宿";
            String xiaohongshuQuery = city.name + " 平价住宿 小红书 酒店 推荐";
            collectSearch("https://www.baidu.com/s?ie=utf-8&wd=" + encode(meituanQuery),
                BAIDU_TITLE, city.name, candidates);
            if (candidates.size() < 5) collectSearch(
                "https://www.baidu.com/s?ie=utf-8&wd=" + encode(xiaohongshuQuery),
                BAIDU_TITLE, city.name, candidates);
            if (candidates.size() < 5) collectSearch(
                "https://cn.bing.com/search?q=" + encode(city.name + " 平价酒店 美团 小红书"),
                BING_TITLE, city.name, candidates);

            List<String> hotels = new ArrayList<>();
            for (String candidate : candidates) {
                hotels.add(candidate);
                if (hotels.size() == 5) break;
            }
            Result result = new Result(hotels,
                "https://www.baidu.com/s?wd=" + encode(meituanQuery + " site:meituan.com"),
                "https://www.xiaohongshu.com/search_result?keyword=" + encode(city.name + " 平价住宿 酒店"));
            synchronized (CACHE) {
                if (CACHE.size() >= 40) CACHE.remove(CACHE.keySet().iterator().next());
                CACHE.put(city.code, result);
            }
            callback.onResult(result);
        });
    }

    private static void collectSearch(String endpoint, Pattern pattern, String city, Set<String> result) {
        try {
            Matcher matcher = pattern.matcher(request(endpoint));
            while (matcher.find() && result.size() < 10) {
                String title = clean(matcher.group(1));
                if (!title.contains(city)) continue;
                title = title
                    .replaceAll("\\s*[-_—|]\\s*(?:\\(|（)?.*$", "")
                    .replaceAll("^(推荐|盘点|攻略)[：:]?", "").trim();
                title = title.replaceAll("^[（(]?" + Pattern.quote(city) + "[）)]?", "").trim();
                if (title.length() < 3 || title.length() > 34 || !looksLikeHotel(title)) continue;
                if (title.contains("预订") || title.contains("排行榜") || title.contains("哪家")
                        || title.contains("十大") || title.contains("前十") || title.contains("攻略")
                        || title.contains("盘点") || title.contains("大家还在搜")
                        || title.contains("携程") || title.contains("去哪儿")) continue;
                result.add(title);
            }
        } catch (Exception ignored) { }
    }

    private static boolean looksLikeHotel(String value) {
        return value.contains("酒店") || value.contains("宾馆") || value.contains("客栈")
            || value.contains("民宿") || value.contains("旅馆") || value.contains("旅店")
            || value.contains("青旅") || value.contains("青年旅舍");
    }

    private static String request(String endpoint) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(4500); connection.setReadTimeout(6500);
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        connection.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/132 Mobile Safari/537.36");
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && body.length() < 2_000_000) body.append(line);
        } finally { connection.disconnect(); }
        return body.toString();
    }

    private static String clean(String value) {
        return value.replaceAll("<[^>]+>", " ").replace("&quot;", "\"")
            .replace("&amp;", "&").replace("&#39;", "'").replace("&nbsp;", " ")
            .replaceAll("&#x?[0-9a-fA-F]+;", " ").replaceAll("\\s+", " ").trim();
    }

    static String meituanSearchUrl(String city) {
        return "https://www.baidu.com/s?wd=" + encode(city + " 平价酒店 美团 人气榜 site:meituan.com");
    }

    static String xiaohongshuSearchUrl(String city) {
        return "https://www.xiaohongshu.com/search_result?keyword=" + encode(city + " 平价住宿 酒店");
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, StandardCharsets.UTF_8.name()); }
        catch (Exception ignored) { return value; }
    }

    private HotelRankService() { }
}
