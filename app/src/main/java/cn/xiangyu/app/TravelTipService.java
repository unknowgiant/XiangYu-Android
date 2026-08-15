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

/** 从小红书公开页面按风险主题读取旅行线索，并提供公开检索入口。 */
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
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
    private static final String[][] RISK_TOPICS = {
        {"出租车与包车", "出租车 包车 黑车 拒载 绕路 议价 宰客"},
        {"低价团与购物", "低价团 强制购物 导游 推销 购物店"},
        {"门票与二次收费", "景区 套票 二次收费 重复售票 园中园 另收费"},
        {"秩序与现场管理", "景区 管理混乱 排队 插队 停车 接驳"},
        {"餐饮住宿消费", "旅游 餐饮 住宿 隐形消费 宰客 退订"}
    };

    static void fetch(CityRepository.City city, Callback callback) {
        fetchRiskTopics(city.code, city.name, callback);
    }

    static void fetchByItem(String city, String item, Callback callback) {
        fetchInternal("detail:" + city, city, item + " 旅游 避坑 出行", callback);
    }

    private static void fetchRiskTopics(String keyPrefix, String city, Callback callback) {
        String key = keyPrefix + ":risk-topics-v2";
        synchronized (CACHE) {
            Result cached = CACHE.get(key);
            if (cached != null) { callback.onResult(cached); return; }
        }
        EXECUTOR.execute(() -> {
            List<String> summaries = new ArrayList<>();
            List<LocalData.Item> tips = new ArrayList<>();
            Set<String> used = new LinkedHashSet<>();
            for (String[] topic : RISK_TOPICS) {
                String endpoint = xiaohongshuSearchUrl(city, topic[1]);
                List<String> titles = readTitles(endpoint, 2, true);
                for (String title : titles) {
                    String normalized = title.replaceAll("[\\p{Punct}\\s]+", "");
                    if (!used.add(normalized)) continue;
                    String summary = topic[0] + "｜" + title;
                    summaries.add(summary);
                    tips.add(new LocalData.Item(keyPrefix + "-risk-" + tips.size(),
                        topic[0] + " · " + shortTitle(title), title,
                        "小红书公开线索 · 发生时间与现状需交叉核实", 0xffa34c3a, "避"));
                    break;
                }
                if (tips.size() >= 8) break;
            }
            Result result = new Result(tips, summaries,
                meituanSearchUrl(city, "旅游 点评 投诉"),
                xiaohongshuSearchUrl(city, "旅游 避坑 出租车 强制购物 二次收费 管理"));
            synchronized (CACHE) {
                if (CACHE.size() >= 40) CACHE.remove(CACHE.keySet().iterator().next());
                CACHE.put(key, result);
            }
            callback.onResult(result);
        });
    }

    /** 通用：抓取当前城市某主题的小红书公开笔记标题，直接展示 2-3 条。 */
    static void fetchNotes(String city, String keyword, int limit, NotesCallback callback) {
        String key = "notes:" + city + ":" + keyword;
        synchronized (NOTES_CACHE) {
            NotesResult cached = NOTES_CACHE.get(key);
            if (cached != null) { callback.onResult(cached); return; }
        }
        EXECUTOR.execute(() -> {
            String xiaohongshuUrl = xiaohongshuSearchUrl(city, keyword);
            String meituanUrl = meituanSearchUrl(city, keyword);
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
            String xiaohongshuUrl = xiaohongshuSearchUrl(city, subject + " 踩坑 注意");
            String meituanUrl = meituanSearchUrl(city, subject);
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
                if (!ContentSafety.isSafeTitle(value)) continue;
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
            || value.contains("时间") || value.contains("路线") || value.contains("出租车")
            || value.contains("包车") || value.contains("黑车") || value.contains("拒载")
            || value.contains("绕路") || value.contains("宰客") || value.contains("强制购物")
            || value.contains("低价团") || value.contains("购物店") || value.contains("二次收费")
            || value.contains("重复售票") || value.contains("另收费") || value.contains("套票")
            || value.contains("园中园") || value.contains("管理混乱") || value.contains("插队")
            || value.contains("停车") || value.contains("接驳") || value.contains("隐形消费")
            || value.contains("退订") || value.contains("投诉");
    }

    private static String meituanSearchUrl(String city, String keyword) {
        return "https://www.meituan.com/s/"
            + encode(city + " " + keyword).replace("+", "%20") + "/";
    }

    private static String xiaohongshuSearchUrl(String city, String keyword) {
        return "https://www.xiaohongshu.com/search_result?keyword=" + encode(city + " " + keyword);
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

    private static String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, StandardCharsets.UTF_8.name()); }
        catch (Exception ignored) { return value; }
    }

    private TravelTipService() { }
}
