package cn.xiangyu.app;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 读取今日头条公开资讯页，并严格筛选当前城市近 90 天的旅行风险报道。 */
final class TravelTipService {
    interface Callback { void onResult(Result result); }
    interface NotesCallback { void onResult(NotesResult result); }

    static final class Result {
        final List<LocalData.Item> tips;
        final List<String> summaries;
        final String meituanUrl;
        final String toutiaoUrl;
        final String xiaohongshuUrl;

        Result(List<LocalData.Item> tips, List<String> summaries,
               String meituanUrl, String toutiaoUrl, String xiaohongshuUrl) {
            this.tips = tips;
            this.summaries = summaries;
            this.meituanUrl = meituanUrl;
            this.toutiaoUrl = toutiaoUrl;
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

    private static final long REPORT_WINDOW_SECONDS = 90L * 24 * 60 * 60;
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final ExecutorService NEWS_EXECUTOR = Executors.newFixedThreadPool(4);
    private static final Map<String, Result> CACHE = new LinkedHashMap<>();
    private static final Map<String, NotesResult> NOTES_CACHE = new LinkedHashMap<>();
    private static final Pattern PUBLIC_TITLE = Pattern.compile(
        "[\\\"'](?:displayTitle|title|desc)[\\\"']\\s*:\\s*[\\\"']([^\\\"']{8,140})[\\\"']",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern RESULT_CARD = Pattern.compile("<div class=\\\"result-content\\\"");
    private static final Pattern CARD_TITLE = Pattern.compile(
        "l-card-title[^>]*>.*?<a[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CARD_ID = Pattern.compile(
        "cr-params=\\\"\\{&quot;gid&quot;:&quot;([0-9]+)&quot;", Pattern.CASE_INSENSITIVE);
    private static final Pattern CARD_TIME = Pattern.compile(
        "&quot;createTime&quot;:&quot;([0-9]{10,13})&quot;", Pattern.CASE_INSENSITIVE);
    private static final Pattern CARD_SOURCE = Pattern.compile(
        "data-log-click=\\\"\\{&quot;pos&quot;:&quot;author&quot;\\}\\\">.*?"
            + "<div[^>]*role=\\\"text\\\">([^<]{2,40})</div>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
    private static final String[] ETHNIC_MARKERS = {
        "土家族", "苗族", "朝鲜族", "藏族", "羌族", "彝族", "布依族", "侗族",
        "哈尼族", "壮族", "傣族", "景颇族", "傈僳族", "蒙古族", "蒙古",
        "柯尔克孜族", "哈萨克族", "回族"
    };
    private static final String[][] CITY_NEWS_ALIASES = {
        {"兴安盟", "乌兰浩特"}, {"锡林郭勒盟", "锡林浩特"}, {"阿拉善盟", "巴彦浩特"},
        {"延边", "延吉"}, {"大兴安岭", "加格达奇"}, {"恩施", "恩施"}, {"湘西", "吉首"},
        {"阿坝", "马尔康"}, {"甘孜", "康定"}, {"凉山", "西昌"},
        {"黔西南", "兴义"}, {"黔东南", "凯里"}, {"黔南", "都匀"},
        {"楚雄", "楚雄"}, {"红河", "蒙自", "建水"}, {"文山", "文山"},
        {"西双版纳", "景洪"}, {"大理", "大理"}, {"德宏", "芒市"},
        {"怒江", "泸水"}, {"迪庆", "香格里拉"}, {"临夏", "临夏"}, {"甘南", "合作"},
        {"海北", "海晏"}, {"黄南", "同仁"}, {"海南藏族", "海南州", "共和"},
        {"果洛", "玛沁"}, {"玉树", "玉树"}, {"海西", "德令哈", "格尔木"},
        {"昌吉", "昌吉"}, {"博尔塔拉", "博乐"}, {"巴音郭楞", "库尔勒"},
        {"克孜勒苏", "阿图什"}, {"伊犁", "伊宁"}
    };
    private static final RiskTopic[] RISK_TOPICS = {
        new RiskTopic("出租车与包车", "出租车", "车", 0xffa34c3a,
            "出租车", "包车", "黑车", "拒载", "绕路", "计价器", "宰客", "网约车"),
        new RiskTopic("低价团与购物", "旅行社", "购", 0xffa06a39,
            "低价团", "强制购物", "购物店", "导游", "跟团", "旅行团", "旅游团", "旅行社"),
        new RiskTopic("门票与二次收费", "景区 门票", "票", 0xff9a6842,
            "门票", "收费", "票价", "套票", "二次消费", "预约", "退票"),
        new RiskTopic("秩序与现场管理", "景区", "序", 0xff786f58,
            "景区", "排队", "拥堵", "管理", "厕所", "停车", "关闭", "限流", "旅拍"),
        new RiskTopic("餐饮住宿消费", "酒店", "价", 0xff8f5f52,
            "餐饮", "饭店", "餐馆", "酒店", "民宿", "住宿", "退订", "宰客", "价格", "账单"),
        new RiskTopic("远郊返程与接驳", "交通", "行", 0xff557181,
            "接驳", "返程", "末班车", "交通", "滞留", "打车", "班车", "摆渡车",
            "公交", "车站", "线路", "道路", "机场", "高铁", "火车")
    };

    private static final class RiskTopic {
        final String name;
        final String query;
        final String mark;
        final int color;
        final String[] matchWords;

        RiskTopic(String name, String query, String mark, int color, String... matchWords) {
            this.name = name;
            this.query = query;
            this.mark = mark;
            this.color = color;
            this.matchWords = matchWords;
        }
    }

    private static final class RecentReport {
        final String id;
        final String title;
        final String source;
        final long publishedAt;
        final boolean riskSpecific;

        RecentReport(String id, String title, String source, long publishedAt) {
            this(id, title, source, publishedAt, true);
        }

        RecentReport(String id, String title, String source, long publishedAt, boolean riskSpecific) {
            this.id = id;
            this.title = title;
            this.source = source;
            this.publishedAt = publishedAt;
            this.riskSpecific = riskSpecific;
        }

        String date() {
            return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
                .format(new Date(publishedAt * 1000));
        }

        String summary() {
            return title + "\n" + date() + " · " + source + " · " + contentLabel();
        }

        String contentLabel() {
            return riskSpecific ? "头条公开风险/避坑内容" : "头条公开旅行提醒补充";
        }

        RecentReport asGeneralAdvice() {
            return new RecentReport(id, title, source, publishedAt, false);
        }
    }

    static void fetch(CityRepository.City city, Callback callback) {
        fetchRiskTopics(city.code, city.name, callback);
    }

    static void fetchByItem(String city, String item, Callback callback) {
        fetchInternal("detail:" + city, city, item, callback);
    }

    private static void fetchRiskTopics(String keyPrefix, String city, Callback callback) {
        String key = keyPrefix + ":risk-topics-v5";
        synchronized (CACHE) {
            Result cached = CACHE.get(key);
            if (cached != null) { callback.onResult(cached); return; }
        }
        callback.onResult(statusRiskResult(keyPrefix, city, true));
        EXECUTOR.execute(() -> {
            List<Future<List<RecentReport>>> jobs = new ArrayList<>();
            for (RiskTopic topic : RISK_TOPICS) {
                jobs.add(NEWS_EXECUTOR.submit(() -> readRecentReports(city, topic, 3)));
            }
            List<List<RecentReport>> grouped = new ArrayList<>();
            Set<String> assigned = new LinkedHashSet<>();
            for (int i = 0; i < RISK_TOPICS.length; i++) {
                List<RecentReport> unique = new ArrayList<>();
                try {
                    for (RecentReport report : jobs.get(i).get()) {
                        if (assigned.add(report.id) && unique.size() < 3) unique.add(report);
                    }
                } catch (Exception ignored) { }
                grouped.add(unique);
            }
            for (RecentReport report : readRecentTravelTips(city, 12)) {
                int topicIndex = classifyTopic(report.title);
                List<RecentReport> reports = grouped.get(topicIndex);
                if (reports.size() < 3 && assigned.add(report.id)) reports.add(report);
            }
            List<String> summaries = new ArrayList<>();
            List<LocalData.Item> tips = new ArrayList<>();
            for (int topicIndex = 0; topicIndex < RISK_TOPICS.length; topicIndex++) {
                RiskTopic topic = RISK_TOPICS[topicIndex];
                List<RecentReport> reports = grouped.get(topicIndex);
                if (reports.isEmpty()) continue;
                for (RecentReport report : reports) {
                    summaries.add(topic.name + "｜" + report.summary());
                    tips.add(new LocalData.Item(
                        keyPrefix + "-risk-" + topicIndex + "-" + report.id,
                        topic.name + " · " + shortTitle(report.title), report.title,
                        report.date() + " · " + report.source + " · " + report.contentLabel(),
                        topic.color, topic.mark));
                }
            }
            if (tips.isEmpty()) tips.add(overallStatusItem(keyPrefix, city, false));
            Result result = new Result(tips, summaries,
                meituanSearchUrl(city, "旅游 点评 投诉"),
                toutiaoSearchUrl(city, "旅游 投诉 景区"),
                xiaohongshuSearchUrl(city, "旅游 避坑 投诉"));
            cache(key, result);
            callback.onResult(result);
        });
    }

    private static Result statusRiskResult(String keyPrefix, String city, boolean loading) {
        List<LocalData.Item> tips = new ArrayList<>();
        tips.add(overallStatusItem(keyPrefix, city, loading));
        return new Result(tips, new ArrayList<>(),
            meituanSearchUrl(city, "旅游 点评 投诉"),
            toutiaoSearchUrl(city, "旅游 投诉 景区"),
            xiaohongshuSearchUrl(city, "旅游 避坑 投诉"));
    }

    private static LocalData.Item overallStatusItem(String keyPrefix, String city, boolean loading) {
        return new LocalData.Item(keyPrefix + "-risk-status",
            loading ? "正在检索近期风险信息" : "近三个月暂无当地结果",
            loading
                ? "正在检索" + city + "近三个月的负面报道、旅行提醒与公开避坑经验。"
                : "暂未检索到同时符合当地、旅游主题和日期要求的公开内容。",
            dateRange() + (loading ? " · 今日头条公开资讯" : " · 不用旧闻或外地内容填充"),
            0xff786f58, "避");
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
        String key = keyPrefix + ":recent-v2:" + subject;
        synchronized (CACHE) {
            Result cached = CACHE.get(key);
            if (cached != null) { callback.onResult(cached); return; }
        }
        RiskTopic topic = findTopic(subject);
        Result empty = emptyDetailResult(city, topic);
        callback.onResult(empty);
        EXECUTOR.execute(() -> {
            List<RecentReport> reports = readRecentReports(city, topic, 3);
            if (reports.isEmpty()) {
                int topicIndex = indexOfTopic(topic);
                reports = new ArrayList<>();
                for (RecentReport report : readRecentTravelTips(city, 12)) {
                    if (classifyTopic(report.title) == topicIndex && reports.size() < 3) reports.add(report);
                }
            }
            if (reports.isEmpty()) {
                cache(key, empty);
                return;
            }
            List<String> summaries = new ArrayList<>();
            List<LocalData.Item> tips = new ArrayList<>();
            for (int i = 0; i < reports.size() && i < 3; i++) {
                RecentReport report = reports.get(i);
                summaries.add(report.summary());
                tips.add(new LocalData.Item(keyPrefix + "-news-tip-" + report.id,
                    shortTitle(report.title), report.title,
                    report.date() + " · " + report.source + " · " + report.contentLabel(),
                    topic.color, topic.mark));
            }
            Result result = new Result(tips, summaries,
                meituanSearchUrl(city, topic.name),
                toutiaoSearchUrl(city, topic.query),
                xiaohongshuSearchUrl(city, topic.name + " 避坑"));
            cache(key, result);
            callback.onResult(result);
        });
    }

    private static Result emptyDetailResult(String city, RiskTopic topic) {
        return new Result(new ArrayList<>(), new ArrayList<>(),
            meituanSearchUrl(city, topic.name),
            toutiaoSearchUrl(city, topic.query),
            xiaohongshuSearchUrl(city, topic.name + " 避坑"));
    }

    private static List<RecentReport> readRecentReports(String city, RiskTopic topic, int limit) {
        List<RecentReport> result = new ArrayList<>();
        for (RecentReport report : readRecentCards(city, topic.query)) {
            if (!matchesTopic(report.title, topic) || !containsNegativeWord(report.title)) continue;
            result.add(report);
            if (result.size() >= limit) break;
        }
        return result;
    }

    private static List<RecentReport> readRecentTravelTips(String city, int limit) {
        List<RecentReport> result = new ArrayList<>();
        addTravelTips(result, readRecentCards(city, "旅游 避坑"), limit, true);
        if (result.isEmpty() && !searchCityName(city).equals(city)) {
            addTravelTips(result, readRecentCards(city, city, "旅游 避坑"), limit, true);
        }
        if (result.isEmpty()) {
            addTravelTips(result, readRecentCards(city, "旅游 攻略"), limit, false);
            if (result.isEmpty() && !searchCityName(city).equals(city)) {
                addTravelTips(result, readRecentCards(city, city, "旅游 攻略"), limit, false);
            }
        }
        return result;
    }

    private static void addTravelTips(List<RecentReport> result, List<RecentReport> candidates,
                                      int limit, boolean requireNegativeWord) {
        for (RecentReport report : candidates) {
            if (!isTravelContext(report.title) || isUnrelatedPromotion(report.title)) continue;
            if (requireNegativeWord ? !containsNegativeWord(report.title)
                    : !containsTravelAdviceWord(report.title)) continue;
            result.add(requireNegativeWord ? report : report.asGeneralAdvice());
            if (result.size() >= limit) break;
        }
    }

    private static List<RecentReport> readRecentCards(String city, String query) {
        return readRecentCards(city, searchCityName(city), query);
    }

    private static List<RecentReport> readRecentCards(String city, String queryCity, String query) {
        List<RecentReport> result = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        try {
            String body = request(toutiaoSearchUrlForName(queryCity, query));
            Matcher cardMatcher = RESULT_CARD.matcher(body);
            List<Integer> starts = new ArrayList<>();
            while (cardMatcher.find()) starts.add(cardMatcher.start());
            long now = System.currentTimeMillis() / 1000;
            for (int i = 0; i < starts.size(); i++) {
                int start = starts.get(i);
                int end = i + 1 < starts.size() ? starts.get(i + 1) : body.length();
                String card = body.substring(start, end);
                String title = matchClean(CARD_TITLE, card);
                String id = match(CARD_ID, card);
                String rawTime = match(CARD_TIME, card);
                if (title.isEmpty() || id.isEmpty() || rawTime.isEmpty()) continue;
                long publishedAt = Long.parseLong(rawTime);
                if (publishedAt > 100_000_000_000L) publishedAt /= 1000;
                if (publishedAt < now - REPORT_WINDOW_SECONDS || publishedAt > now + 24 * 60 * 60) continue;
                if (!matchesCity(title, city) || !ContentSafety.isSafeTitle(title)) continue;
                String normalized = title.replaceAll("[\\p{Punct}\\s]+", "");
                if (!used.add(normalized)) continue;
                String source = matchClean(CARD_SOURCE, card);
                if (source.isEmpty()) source = "头条公开资讯";
                result.add(new RecentReport(id, limit(title, 140), limit(source, 24), publishedAt));
            }
        } catch (Exception ignored) { }
        return result;
    }

    private static int classifyTopic(String title) {
        for (int i = 0; i < RISK_TOPICS.length; i++) {
            if (matchesTopic(title, RISK_TOPICS[i])) return i;
        }
        return 3;
    }

    private static int indexOfTopic(RiskTopic topic) {
        for (int i = 0; i < RISK_TOPICS.length; i++) if (RISK_TOPICS[i] == topic) return i;
        return 3;
    }

    private static boolean isTravelContext(String value) {
        return value.contains("旅游") || value.contains("旅行") || value.contains("景区")
            || value.contains("景点") || value.contains("游玩") || value.contains("民宿")
            || value.contains("酒店") || value.contains("住宿") || value.contains("导游")
            || value.contains("门票") || value.contains("古城") || value.contains("老城")
            || value.contains("公园") || value.contains("博物馆") || value.contains("乐园")
            || value.contains("度假") || value.contains("自驾") || value.contains("旅拍")
            || value.contains("伴手礼") || value.contains("特产") || value.contains("夜市")
            || value.contains("美食") || value.contains("餐饮") || value.contains("打卡")
            || value.contains("徒步");
    }

    private static boolean isUnrelatedPromotion(String value) {
        return value.contains("流量卡") || value.contains("宽带") || value.contains("装修")
            || value.contains("黄金回收") || value.contains("名表回收") || value.contains("租号")
            || value.contains("眼科") || value.contains("医院") || value.contains("婚纱照")
            || value.contains("购车") || value.contains("4S店") || value.contains("4S 店")
            || value.contains("托运") || value.contains("全屋定制") || value.contains("房产");
    }

    private static boolean containsTravelAdviceWord(String value) {
        return value.contains("攻略") || value.contains("指南") || value.contains("提醒")
            || value.contains("提示") || value.contains("注意") || value.contains("必看")
            || value.contains("FAQ") || value.contains("避开") || value.contains("少走弯路")
            || value.contains("实话实说") || value.contains("别去") || value.contains("勿去");
    }

    private static List<String> readTitles(String endpoint, int limit, boolean tipOnly) {
        Set<String> result = new LinkedHashSet<>();
        try {
            Matcher matcher = PUBLIC_TITLE.matcher(request(endpoint));
            while (matcher.find() && result.size() < limit) {
                String value = clean(matcher.group(1));
                if (value.length() < 8 || value.contains("登录") || value.contains("搜索小红书")) continue;
                if (!ContentSafety.isSafeTitle(value)) continue;
                if (tipOnly && !containsNegativeWord(value)) continue;
                result.add(limit(value, 140));
            }
        } catch (Exception ignored) { }
        return new ArrayList<>(result);
    }

    private static boolean matchesCity(String title, String city) {
        for (String alias : cityAliases(city)) if (title.contains(alias)) return true;
        return false;
    }

    private static List<String> cityAliases(String city) {
        List<String> result = new ArrayList<>();
        result.add(city);
        String base = city.replaceFirst("(地区|市)$", "");
        if (city.endsWith("自治州")) {
            int cut = city.length();
            for (String marker : ETHNIC_MARKERS) {
                int index = city.indexOf(marker);
                if (index >= 2 && index < cut) cut = index;
            }
            base = city.substring(0, cut);
            result.add(base + "州");
            if (base.length() >= 3) result.add(base);
        } else if (!base.equals(city)) {
            result.add(base);
        }
        for (String[] entry : CITY_NEWS_ALIASES) {
            if (!city.startsWith(entry[0])) continue;
            for (int i = 1; i < entry.length; i++) {
                if (!result.contains(entry[i])) result.add(entry[i]);
            }
            break;
        }
        return result;
    }

    private static boolean matchesTopic(String title, RiskTopic topic) {
        for (String word : topic.matchWords) if (title.contains(word)) return true;
        return false;
    }

    private static boolean containsNegativeWord(String value) {
        return value.contains("投诉") || value.contains("被打") || value.contains("起诉")
            || value.contains("宰客") || value.contains("强制") || value.contains("拒载")
            || value.contains("绕路") || value.contains("黑车") || value.contains("加价")
            || value.contains("乱收费") || value.contains("二次收费") || value.contains("退票难")
            || value.contains("退订难") || value.contains("拥堵") || value.contains("滞留")
            || value.contains("混乱") || value.contains("关闭") || value.contains("停售")
            || value.contains("排长队") || value.contains("兜售") || value.contains("打卡难")
            || value.contains("曝光") || value.contains("通报") || value.contains("查处")
            || value.contains("处罚") || value.contains("纠纷") || value.contains("维权")
            || value.contains("退款") || value.contains("事故") || value.contains("避坑")
            || value.contains("踩坑") || value.contains("踩雷") || value.contains("避雷")
            || value.contains("套路") || value.contains("不要去") || value.contains("劝阻")
            || value.contains("暂停") || value.contains("停运") || value.contains("闭园")
            || value.contains("限行") || value.contains("封闭") || value.contains("临时调整")
            || value.contains("故障") || value.contains("延误") || value.contains("积水")
            || value.contains("受阻") || value.contains("取消") || value.contains("涨价")
            || value.contains("不合理") || value.contains("卫生问题") || value.contains("脏乱")
            || value.contains("强买强卖") || value.contains("欺诈") || value.contains("受骗")
            || value.contains("被骗") || value.contains("失联") || value.contains("安全隐患");
    }

    private static RiskTopic findTopic(String subject) {
        for (RiskTopic topic : RISK_TOPICS) {
            if (subject.contains(topic.name)) return topic;
            for (String word : topic.matchWords) if (subject.contains(word)) return topic;
        }
        return RISK_TOPICS[3];
    }

    private static String meituanSearchUrl(String city, String keyword) {
        return "https://www.meituan.com/s/"
            + encode(city + " " + keyword).replace("+", "%20") + "/";
    }

    private static String xiaohongshuSearchUrl(String city, String keyword) {
        return "https://www.xiaohongshu.com/search_result?keyword=" + encode(city + " " + keyword);
    }

    private static String toutiaoSearchUrl(String city, String keyword) {
        return toutiaoSearchUrlForName(searchCityName(city), keyword);
    }

    private static String toutiaoSearchUrlForName(String city, String keyword) {
        return "https://so.toutiao.com/search?pd=information&source=input&keyword="
            + encode(city + " " + keyword);
    }

    private static String searchCityName(String city) {
        if (city.endsWith("地区")) return city.substring(0, city.length() - 2);
        if (!city.endsWith("自治州")) return city;
        int cut = city.length();
        for (String marker : ETHNIC_MARKERS) {
            int index = city.indexOf(marker);
            if (index >= 2 && index < cut) cut = index;
        }
        return city.substring(0, cut);
    }

    private static String shortTitle(String value) {
        int end = value.indexOf('：');
        return limit(end > 3 ? value.substring(0, end) : value, 18);
    }

    private static String dateRange() {
        long now = System.currentTimeMillis();
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        return format.format(new Date(now - REPORT_WINDOW_SECONDS * 1000))
            + " 至 " + format.format(new Date(now));
    }

    private static String request(String endpoint) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(9000);
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        connection.setRequestProperty("Accept", "text/html,application/json,text/plain,*/*");
        connection.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/132 Safari/537.36");
        if (endpoint.contains("toutiao.com")) {
            connection.setRequestProperty("Referer", "https://so.toutiao.com/");
        }
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

    private static String match(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String matchClean(Pattern pattern, String value) {
        return clean(match(pattern, value));
    }

    private static String clean(String value) {
        return decodeUnicode(value).replace("\\\"", "\"").replaceAll("<[^>]+>", " ")
            .replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&#39;", "'")
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

    private static void cache(String key, Result result) {
        synchronized (CACHE) {
            if (CACHE.size() >= 40) CACHE.remove(CACHE.keySet().iterator().next());
            CACHE.put(key, result);
        }
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
