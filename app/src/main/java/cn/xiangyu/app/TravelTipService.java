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

/** 从小红书公开页面读取近期旅行、出行与探店笔记，并提供美团、小红书搜索入口。 */
final class TravelTipService {
    interface Callback { void onResult(Result result); }
    interface NotesCallback { void onResult(NotesResult result); }

    static final class Result {
        final List<LocalData.Item> tips;
        final List<String> summaries;
        final String meituanUrl;
        final String xiaohongshuUrl;

        Result(List<LocalData.Item> tips, List<String> summaries,
               String meituanUrl, String xiaohongshuUrl) {
            this.tips = tips;
            this.summaries = summaries;
            this.meituanUrl = meituanUrl;
            this.xiaohongshuUrl = xiaohongshuUrl;
        }
    }

    static final class NotesResult {
        final List<String> notes;
        final String meituanUrl;
        final String xiaohongshuUrl;

        NotesResult(List<String> notes, String meituanUrl, String xiaohongshuUrl) {
            this.notes = notes;
            this.meituanUrl = meituanUrl;
            this.xiaohongshuUrl = xiaohongshuUrl;
        }
    }

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Map<String, Result> CACHE = new LinkedHashMap<>();
    private static final Map<String, NotesResult> NOTES_CACHE = new LinkedHashMap<>();
    private static final Pattern PUBLIC_TITLE = Pattern.compile(
        "[\\\"'](?:displayTitle|title|desc)[\\\"']\\s*:\\s*[\\\"']([^\\\"']{8,140})[\\\"']",
        Pattern.CASE_INSENSITIVE);

    static void fetch(CityRepository.City city, Callback callback) {
        fetchInternal(city.code, city.name, "旅游 出行 避坑", callback);
    }

    static void fetchByItem(String city, String item, Callback callback) {
        fetchInternal("detail:" + city, city, item + " 旅游 避坑 出行", callback);
    }

    /** 通用：抓取当前城市某主题的小红书公开笔记标题，直接展示 2-3 条。 */
    static void fetchNotes(String city, String keyword, int limit, NotesCallback callback) {
        String key = "notes:" + city + ":" + keyword;
        synchronized (NOTES_CACHE) {
            NotesResult cached = NOTES_CACHE.get(key);
            if (cached != null) { callback.onResult(cached); return; }
        }
        EXECUTOR.execute(() -> {
            String xiaohongshuUrl = "https://www.xiaohongshu.com/search_result?keyword="
                + encode(city + " " + keyword);
            String meituanUrl = "https://www.meituan.com/s/"
                + encode(city + " " + keyword).replace("+", "%20") + "/";
            List<String> notes = readTitles(xiaohongshuUrl, limit, false);
            NotesResult result = new NotesResult(notes, meituanUrl, xiaohongshuUrl);
            synchronized (NOTES_CACHE) {
                if (NOTES_CACHE.size() >= 40) NOTES_CACHE.remove(NOTES_CACHE.keySet().iterator().next());
                NOTES_CACHE.put(key, result);
            }
            callback.onResult(result);
        });
    }

    private static void fetchInternal(String keyPrefix, String city, String subject, Callback callback) {
        String key = keyPrefix + ":" + subject;
        synchronized (CACHE) {
            Result cached = CACHE.get(key);
            if (cached != null) { callback.onResult(cached); return; }
        }
        EXECUTOR.execute(() -> {
            String xiaohongshuUrl = "https://www.xiaohongshu.com/search_result?keyword="
                + encode(city + " " + subject + " 踩坑 注意");
            String meituanUrl = "https://www.meituan.com/s/"
                + encode(city + " " + subject).replace("+", "%20") + "/";
            List<String> summaries = readTitles(xiaohongshuUrl, 6, true);
            List<LocalData.Item> tips = new ArrayList<>();
            for (int i = 0; i < summaries.size() && i < 6; i++) {
                String summary = summaries.get(i);
                tips.add(new LocalData.Item(keyPrefix + "-xhs-tip-" + i,
                    shortTitle(summary), summary,
                    "小红书 · 公开页面线索 · 发布日期与现状需复核", 0xffa34c3a, "避"));
            }
            Result result = new Result(tips, summaries, meituanUrl, xiaohongshuUrl);
            synchronized (CACHE) {
                if (CACHE.size() >= 40) CACHE.remove(CACHE.keySet().iterator().next());
                CACHE.put(key, result);
            }
            callback.onResult(result);
        });
    }

    private static List<String> readTitles(String endpoint, int limit, boolean tipOnly) {
        Set<String> result = new LinkedHashSet<>();
        try {
            Matcher matcher = PUBLIC_TITLE.matcher(request(endpoint));
            while (matcher.find() && result.size() < limit) {
                String value = clean(matcher.group(1));
                if (value.length() < 8 || value.contains("登录") || value.contains("搜索小红书")) continue;
                if (tipOnly && !containsTipWord(value)) continue;
                result.add(limit(value, 140));
            }
        } catch (Exception ignored) { }
        return new ArrayList<>(result);
    }

    private static boolean containsTipWord(String value) {
        return value.contains("避坑") || value.contains("注意") || value.contains("不要")
            || value.contains("谨慎") || value.contains("踩坑") || value.contains("攻略")
            || value.contains("排队") || value.contains("价格") || value.contains("预约")
            || value.contains("出行") || value.contains("交通") || value.contains("门票")
            || value.contains("时间") || value.contains("路线");
    }

    private static String shortTitle(String value) {
        int end = value.indexOf('：');
        return limit(end > 3 ? value.substring(0, end) : value, 18);
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
        return value.replace("\\\"", "\"").replaceAll("<[^>]+>", " ")
            .replace("&quot;", "\"").replace("&amp;", "&").replace("&#39;", "'")
            .replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
    }

    private static String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, StandardCharsets.UTF_8.name()); }
        catch (Exception ignored) { return value; }
    }

    private TravelTipService() { }
}
