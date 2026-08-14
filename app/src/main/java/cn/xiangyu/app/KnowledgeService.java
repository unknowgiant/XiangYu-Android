package cn.xiangyu.app;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class KnowledgeService {
    interface Callback { void onResult(Result result); }

    static final class Result {
        final String introduction;
        final String source;
        final String baikeUrl;
        final String baiduSearchUrl;
        final String bingChinaUrl;

        Result(String introduction, String source, String baikeUrl,
               String baiduSearchUrl, String bingChinaUrl) {
            this.introduction = introduction;
            this.source = source;
            this.baikeUrl = baikeUrl;
            this.baiduSearchUrl = baiduSearchUrl;
            this.bingChinaUrl = bingChinaUrl;
        }
    }

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Map<String, Result> CACHE = new LinkedHashMap<>();
    private static final Pattern[] META_PATTERNS = {
        Pattern.compile("<meta\\s+name=[\\\"']description[\\\"']\\s+content=[\\\"'](.*?)[\\\"']", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
        Pattern.compile("<meta\\s+content=[\\\"'](.*?)[\\\"']\\s+name=[\\\"']description[\\\"']", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
        Pattern.compile("<meta\\s+property=[\\\"']og:description[\\\"']\\s+content=[\\\"'](.*?)[\\\"']", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
    };

    static void fetch(String city, LocalData.Item item, int category, Callback callback) {
        String query = city + " " + item.title + categoryWord(category);
        String cacheKey = category + ":" + query;
        synchronized (CACHE) {
            Result cached = CACHE.get(cacheKey);
            if (cached != null) { callback.onResult(cached); return; }
        }
        EXECUTOR.execute(() -> {
            String baike = searchUrl("https://baike.baidu.com/search/word?word=", city + " " + item.title);
            String baidu = searchUrl("https://www.baidu.com/s?wd=", query);
            String bing = searchUrl("https://cn.bing.com/search?q=", query);
            String introduction = "";
            String source = "本地介绍 · 大陆可访问来源";

            try { introduction = fetchBaikeSummary(baike, item.title); }
            catch (Exception ignored) { }
            if (!introduction.isEmpty()) {
                source = "百度百科 · 互联网资料";
            } else {
                try { introduction = fetchSearchSummary(baidu, item.title, true); }
                catch (Exception ignored) { }
                if (!introduction.isEmpty()) {
                    source = "百度搜索 · 互联网资料";
                } else {
                    try { introduction = fetchSearchSummary(bing, item.title, false); }
                    catch (Exception ignored) { }
                    if (!introduction.isEmpty()) source = "必应中国 · 互联网资料";
                }
            }

            Result result = new Result(introduction, source, baike, baidu, bing);
            synchronized (CACHE) {
                if (CACHE.size() >= 30) CACHE.remove(CACHE.keySet().iterator().next());
                CACHE.put(cacheKey, result);
            }
            callback.onResult(result);
        });
    }

    private static String categoryWord(int category) {
        return category == 0 ? " 美食 介绍" : category == 1 ? " 民俗 文化 介绍"
            : category == 2 ? " 景区 历史 看点 游览攻略" : " 平价住宿 体验";
    }

    private static String searchUrl(String prefix, String query) {
        try { return prefix + URLEncoder.encode(query, StandardCharsets.UTF_8.name()); }
        catch (Exception ignored) { return prefix; }
    }

    private static String fetchBaikeSummary(String endpoint, String title) throws Exception {
        String html = requestText(endpoint);
        for (Pattern pattern : META_PATTERNS) {
            Matcher matcher = pattern.matcher(html);
            if (matcher.find()) {
                String candidate = acceptedSummary(matcher.group(1), title);
                if (!candidate.isEmpty() && !candidate.contains("内容开放、自由的网络百科全书")) return candidate;
            }
        }
        return "";
    }

    private static String fetchSearchSummary(String endpoint, String title, boolean baidu) throws Exception {
        String html = requestText(endpoint);
        String[] expressions = baidu
            ? new String[]{
                "<div[^>]+class=[\\\"'][^\\\"']*c-abstract[^\\\"']*[\\\"'][^>]*>(.*?)</div>",
                "<span[^>]+class=[\\\"'][^\\\"']*content-right[^\\\"']*[\\\"'][^>]*>(.*?)</span>"
            }
            : new String[]{
                "<li[^>]+class=[\\\"'][^\\\"']*b_algo[^\\\"']*[\\\"'][^>]*>.*?<p>(.*?)</p>",
                "<div[^>]+class=[\\\"'][^\\\"']*b_caption[^\\\"']*[\\\"'][^>]*>.*?<p>(.*?)</p>"
            };
        for (String expression : expressions) {
            Matcher matcher = Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
            while (matcher.find()) {
                String candidate = acceptedSummary(matcher.group(1), title);
                if (!candidate.isEmpty()) return candidate;
            }
        }
        return "";
    }

    private static String requestText(String endpoint) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(3500);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/132 Mobile Safari/537.36 XiangYu/1.4.8");
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int total = 0;
            while ((line = reader.readLine()) != null && total < 650000) {
                body.append(line);
                total += line.length();
            }
        } finally {
            connection.disconnect();
        }
        return body.toString();
    }

    private static String acceptedSummary(String raw, String title) {
        String value = clean(raw);
        if (value.length() < 20 || !value.contains(title)) return "";
        return value.length() > 240 ? value.substring(0, 240) + "…" : value;
    }

    private static String clean(String value) {
        return value.replaceAll("<script.*?</script>", " ")
            .replaceAll("<style.*?</style>", " ")
            .replaceAll("<[^>]+>", " ")
            .replace("&quot;", "\"").replace("&amp;", "&").replace("&#39;", "'")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replaceAll("&#x([0-9a-fA-F]+);", " ").replaceAll("&#[0-9]+;", " ")
            .replaceAll("\\s+", " ").trim();
    }

    private KnowledgeService() { }
}
