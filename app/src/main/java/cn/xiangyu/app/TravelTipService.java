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

final class TravelTipService {
    interface Callback { void onResult(Result result); }

    static final class Result {
        final List<LocalData.Item> tips;
        final List<String> summaries;
        final String xiaohongshuUrl;
        final String douyinUrl;
        final String baiduUrl;

        Result(List<LocalData.Item> tips, List<String> summaries,
               String xiaohongshuUrl, String douyinUrl, String baiduUrl) {
            this.tips = tips;
            this.summaries = summaries;
            this.xiaohongshuUrl = xiaohongshuUrl;
            this.douyinUrl = douyinUrl;
            this.baiduUrl = baiduUrl;
        }
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Map<String, Result> CACHE = new LinkedHashMap<>();
    private static final Pattern RESULT_BLOCK = Pattern.compile(
        "<div[^>]+class=[\"'][^\"']*(?:result|c-container)[^\"']*[\"'][^>]*>(.*?)</div>\\s*</div>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<h3[^>]*>.*?<a[^>]*>(.*?)</a>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ABSTRACT = Pattern.compile(
        "<(?:div|span)[^>]+class=[\"'][^\"']*(?:c-abstract|content-right)[^\"']*[\"'][^>]*>(.*?)</(?:div|span)>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern JSON_TITLE = Pattern.compile(
        "[\"'](?:title|titleContent)[\"']\\s*:\\s*[\"'](.*?)[\"']",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    static void fetch(CityRepository.City city, Callback callback) {
        fetchInternal(city.code, city.name, "旅游 避坑", callback);
    }

    static void fetchByItem(String city, String item, Callback callback) {
        fetchInternal("detail:" + city, city, item + " 旅游 避坑", callback);
    }

    private static void fetchInternal(String keyPrefix, String city, String subject, Callback callback) {
        String key = keyPrefix + ":" + subject;
        synchronized (CACHE) {
            Result cached = CACHE.get(key);
            if (cached != null) { callback.onResult(cached); return; }
        }
        EXECUTOR.execute(() -> {
            String xhsQuery = "site:xiaohongshu.com " + city + " " + subject + " 踩坑 注意";
            String douyinQuery = "site:douyin.com " + city + " " + subject + " 踩坑 注意";
            List<String> summaries = new ArrayList<>();
            try { appendUnique(summaries, searchBaidu(xhsQuery, "小红书"), 4); }
            catch (Exception ignored) { }
            try { appendUnique(summaries, searchBaidu(douyinQuery, "抖音"), 6); }
            catch (Exception ignored) { }
            List<LocalData.Item> tips = new ArrayList<>();
            int index = 0;
            for (String summary : summaries) {
                tips.add(new LocalData.Item(keyPrefix + "-xhs-tip-" + index,
                    shortTitle(summary), summary,
                    sourceOf(summary) + "公开搜索线索 · 发布日期与现状需复核", 0xffa34c3a, "避"));
                if (++index == 6) break;
            }
            Result result = new Result(tips, summaries,
                "https://www.xiaohongshu.com/search_result?keyword=" + encode(city + " " + subject),
                "https://www.douyin.com/search/" + encode(city + " " + subject) + "?type=general",
                "https://www.baidu.com/s?wd=" + encode("(" + xhsQuery + ") OR (" + douyinQuery + ")"));
            synchronized (CACHE) {
                if (CACHE.size() >= 40) CACHE.remove(CACHE.keySet().iterator().next());
                CACHE.put(key, result);
            }
            callback.onResult(result);
        });
    }

    private static List<String> searchBaidu(String query, String source) throws Exception {
        String html = request("https://www.baidu.com/s?ie=utf-8&wd=" + encode(query));
        Set<String> result = new LinkedHashSet<>();
        Matcher blocks = RESULT_BLOCK.matcher(html);
        while (blocks.find() && result.size() < 4) {
            String block = blocks.group(1);
            String title = first(TITLE, block);
            String detail = first(ABSTRACT, block);
            String value = clean(title + (detail.isEmpty() ? "" : "：" + detail));
            addResult(result, value, source, 120);
        }
        if (result.size() < 2) {
            Matcher titles = TITLE.matcher(html);
            while (titles.find() && result.size() < 4) {
                addResult(result, clean(titles.group(1)), source, 100);
            }
        }
        if (result.size() < 2) {
            Matcher jsonTitles = JSON_TITLE.matcher(html);
            while (jsonTitles.find() && result.size() < 4) {
                addResult(result, clean(jsonTitles.group(1).replace("\\u003c", "<")
                    .replace("\\u003e", ">").replace("\\\"", "\"")), source, 100);
            }
        }
        return new ArrayList<>(result);
    }

    private static void addResult(Set<String> result, String value, String source, int max) {
        if (value.length() < 8 || !containsTipWord(value)) return;
        if (value.contains("百度百科") || value.contains("登录") || value.contains("验证码")) return;
        result.add("【" + source + "】" + limit(value, max));
    }

    private static void appendUnique(List<String> target, List<String> source, int max) {
        for (String value : source) {
            if (!target.contains(value)) target.add(value);
            if (target.size() >= max) return;
        }
    }

    private static boolean containsTipWord(String value) {
        return value.contains("避坑") || value.contains("注意") || value.contains("不要")
            || value.contains("谨慎") || value.contains("踩坑") || value.contains("攻略")
            || value.contains("排队") || value.contains("价格") || value.contains("预约");
    }

    private static String shortTitle(String value) {
        String clean = value.replaceFirst("^【[^】]+】", "");
        int end = clean.indexOf('：');
        String title = end > 3 ? clean.substring(0, end) : clean;
        return limit(title, 18);
    }

    private static String sourceOf(String value) {
        if (value.startsWith("【抖音】")) return "抖音";
        return "小红书";
    }

    private static String first(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1) : "";
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
        return value.replaceAll("<script.*?</script>", " ").replaceAll("<style.*?</style>", " ")
            .replaceAll("<[^>]+>", " ").replace("&quot;", "\"").replace("&amp;", "&")
            .replace("&#39;", "'").replace("&nbsp;", " ")
            .replaceAll("&#x?[0-9a-fA-F]+;", " ").replaceAll("\\s+", " ").trim();
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
