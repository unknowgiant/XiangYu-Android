package cn.xiangyu.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TransportService {
    interface Callback { void onResult(TrainResult result); }

    static final class Train {
        final String code;
        final String departure;
        final String arrival;
        final String duration;

        Train(String code, String departure, String arrival, String duration) {
            this.code = code;
            this.departure = departure;
            this.arrival = arrival;
            this.duration = duration;
        }
    }

    static final class TrainResult {
        final String date;
        final String fromStation;
        final String toStation;
        final List<Train> trains;
        final boolean fresh;
        final String officialUrl;

        TrainResult(String date, String fromStation, String toStation, List<Train> trains,
                    boolean fresh, String officialUrl) {
            this.date = date;
            this.fromStation = fromStation;
            this.toStation = toStation;
            this.trains = trains;
            this.fresh = fresh;
            this.officialUrl = officialUrl;
        }
    }

    private static final int TRANSPORT_COLOR = 0xff356d78;
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Map<String, String> CAPITALS = capitals();
    private static final Map<String, String> MAIN_STATIONS = stations();
    private static final Map<String, String> STATION_CODES = new HashMap<>();
    private static final Map<String, TrainResult> TRAIN_CACHE = new LinkedHashMap<>();
    private static boolean stationCodesLoaded;
    private static final Pattern QUERY_PATH = Pattern.compile("CLeftTicketUrl\\s*=\\s*['\"]([^'\"]+)['\"]");

    static List<LocalData.Item> items(CityRepository repository, CityRepository.City city,
                                      List<LocalData.Item> sights) {
        if (city == null) return Collections.emptyList();
        List<LocalData.Item> result = new ArrayList<>();
        String station = mainStation(city);
        int limit = Math.min(8, sights.size());
        for (int i = 0; i < limit; i++) {
            LocalData.Item sight = sights.get(i);
            result.add(new LocalData.Item(city.code + "-transport-sight-" + i,
                station + " → " + sight.title,
                "从市内主铁路枢纽出发，优先查看地铁、公交或景区接驳；远郊景点需预留返程时间。",
                sight.hasLocation() ? "实时公共交通规划 · 景点坐标已匹配" : "实时公共交通规划 · 按景点名称检索",
                TRANSPORT_COLOR, "行", sight.lat, sight.lon));
        }
        if (isCapital(city)) {
            for (CityRepository.City destination : repository.inProvince(city.province)) {
                if (destination.code.equals(city.code)) continue;
                result.add(new LocalData.Item(city.code + "-transport-rail-" + destination.code,
                    station + " → " + destination.name,
                    "查询前往" + destination.name + "的次日直达高铁、动车和普速列车，显示发车、到达与历时。",
                    "省会出发 · 12306 实时车次",
                    0xff586b83, "铁", destination.lat, destination.lon));
            }
        }
        return result;
    }

    static boolean isRailItem(LocalData.Item item) {
        return item.id.contains("-transport-rail-");
    }

    static String destinationCode(LocalData.Item item) {
        int marker = item.id.indexOf("-transport-rail-");
        return marker < 0 ? "" : item.id.substring(marker + "-transport-rail-".length());
    }

    static String scenicName(LocalData.Item item) {
        int arrow = item.title.indexOf(" → ");
        return arrow < 0 ? item.title : item.title.substring(arrow + 3);
    }

    static String mainStation(CityRepository.City city) {
        String override = MAIN_STATIONS.get(city.name);
        return override == null ? city.name + "站" : override;
    }

    static String baiduTransitUrl(CityRepository.City city, String destination) {
        return "https://api.map.baidu.com/direction?origin=" + encode(mainStation(city))
            + "&destination=" + encode(destination) + "&mode=transit&region="
            + encode(city.name) + "&output=html&src=cn.xiangyu.app";
    }

    static String amapTransitUrl(CityRepository.City city, String destination) {
        String query = mainStation(city) + " 到 " + destination + " 公共交通";
        return "https://uri.amap.com/search?keyword=" + encode(query) + "&city=" + encode(city.name);
    }

    static void fetchTrains(CityRepository.City from, CityRepository.City to, Callback callback) {
        String date = LocalDate.now().plusDays(1).toString();
        String key = date + ":" + from.code + ":" + to.code;
        synchronized (TRAIN_CACHE) {
            TrainResult cached = TRAIN_CACHE.get(key);
            if (cached != null) { callback.onResult(cached); return; }
        }
        EXECUTOR.execute(() -> {
            String fromStation = mainStation(from);
            String toStation = mainStation(to);
            List<Train> trains = new ArrayList<>();
            boolean fresh = false;
            try {
                ensureStationCodes();
                String fromCode = findStationCode(fromStation, from.name);
                String toCode = findStationCode(toStation, to.name);
                if (fromCode != null && toCode != null) {
                    Session session = openSession();
                    String endpoint = "https://kyfw.12306.cn/otn/" + session.queryPath
                        + "?leftTicketDTO.train_date="
                        + encode(date) + "&leftTicketDTO.from_station=" + encode(fromCode)
                        + "&leftTicketDTO.to_station=" + encode(toCode) + "&purpose_codes=ADULT";
                    JSONObject root = new JSONObject(request(endpoint,
                        "https://kyfw.12306.cn/otn/leftTicket/init", session.cookie));
                    JSONObject data = root.optJSONObject("data");
                    JSONArray values = data == null ? null : data.optJSONArray("result");
                    if (values != null) {
                        for (int i = 0; i < values.length(); i++) {
                            String[] fields = values.optString(i).split("\\|", -1);
                            if (fields.length <= 10 || fields[3].isEmpty()) continue;
                            trains.add(new Train(fields[3], fields[8], fields[9], fields[10]));
                        }
                        trains.sort(Comparator.comparingInt(value -> trainPriority(value.code)));
                        if (trains.size() > 10) trains = new ArrayList<>(trains.subList(0, 10));
                        fresh = true;
                    }
                }
            } catch (Exception ignored) { }
            String official = "https://kyfw.12306.cn/otn/leftTicket/init";
            TrainResult result = new TrainResult(date, fromStation, toStation, trains, fresh, official);
            synchronized (TRAIN_CACHE) {
                if (TRAIN_CACHE.size() >= 40) TRAIN_CACHE.remove(TRAIN_CACHE.keySet().iterator().next());
                TRAIN_CACHE.put(key, result);
            }
            callback.onResult(result);
        });
    }

    private static synchronized void ensureStationCodes() throws Exception {
        if (stationCodesLoaded) return;
        String body = request("https://kyfw.12306.cn/otn/resources/js/framework/station_name.js",
            "https://kyfw.12306.cn/", "");
        for (String record : body.split("@")) {
            String[] fields = record.split("\\|");
            if (fields.length >= 4 && !fields[2].isEmpty() && !fields[3].isEmpty()) {
                STATION_CODES.put(fields[2], fields[3]);
            }
        }
        stationCodesLoaded = !STATION_CODES.isEmpty();
        if (!stationCodesLoaded) throw new IllegalStateException("No railway station index");
    }

    private static String findStationCode(String preferred, String city) {
        String exact = STATION_CODES.get(preferred.replaceFirst("站$", ""));
        if (exact != null) return exact;
        exact = STATION_CODES.get(city);
        if (exact != null) return exact;
        for (String suffix : Arrays.asList("东", "南", "西", "北")) {
            exact = STATION_CODES.get(city + suffix);
            if (exact != null) return exact;
        }
        return null;
    }

    private static int trainPriority(String code) {
        if (code.startsWith("G")) return 0;
        if (code.startsWith("D") || code.startsWith("C")) return 1;
        return 2;
    }

    private static Session openSession() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
            "https://kyfw.12306.cn/otn/leftTicket/init").openConnection();
        configure(connection, "https://kyfw.12306.cn/");
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status);
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && body.length() < 2_000_000) body.append(line);
        }
        List<String> cookies = connection.getHeaderFields().get("Set-Cookie");
        if (cookies == null) cookies = connection.getHeaderFields().get("set-cookie");
        StringBuilder cookie = new StringBuilder();
        if (cookies != null) for (String value : cookies) {
            if (cookie.length() > 0) cookie.append("; ");
            cookie.append(value.split(";", 2)[0]);
        }
        connection.disconnect();
        Matcher matcher = QUERY_PATH.matcher(body);
        String path = matcher.find() ? matcher.group(1) : "leftTicket/query";
        return new Session(path, cookie.toString());
    }

    private static String request(String endpoint, String referer, String cookie) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        configure(connection, referer);
        if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status);
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && body.length() < 5_000_000) body.append(line);
        } finally { connection.disconnect(); }
        return body.toString();
    }

    private static void configure(HttpURLConnection connection, String referer) {
        connection.setConnectTimeout(7000); connection.setReadTimeout(9000);
        connection.setRequestProperty("Accept", "application/json,text/javascript,*/*;q=0.8");
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        connection.setRequestProperty("Referer", referer);
        connection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/132 Mobile Safari/537.36");
    }

    private static final class Session {
        final String queryPath;
        final String cookie;
        Session(String queryPath, String cookie) { this.queryPath = queryPath; this.cookie = cookie; }
    }

    private static boolean isCapital(CityRepository.City city) {
        return city.name.equals(CAPITALS.get(city.province));
    }

    private static Map<String, String> capitals() {
        Map<String, String> values = new HashMap<>();
        String[][] rows = {
            {"北京市", "北京"}, {"天津市", "天津"}, {"河北省", "石家庄"}, {"山西省", "太原"},
            {"内蒙古自治区", "呼和浩特"}, {"辽宁省", "沈阳"}, {"吉林省", "长春"}, {"黑龙江省", "哈尔滨"},
            {"上海市", "上海"}, {"江苏省", "南京"}, {"浙江省", "杭州"}, {"安徽省", "合肥"},
            {"福建省", "福州"}, {"江西省", "南昌"}, {"山东省", "济南"}, {"河南省", "郑州"},
            {"湖北省", "武汉"}, {"湖南省", "长沙"}, {"广东省", "广州"}, {"广西壮族自治区", "南宁"},
            {"海南省", "海口"}, {"重庆市", "重庆"}, {"四川省", "成都"}, {"贵州省", "贵阳"},
            {"云南省", "昆明"}, {"西藏自治区", "拉萨"}, {"陕西省", "西安"}, {"甘肃省", "兰州"},
            {"青海省", "西宁"}, {"宁夏回族自治区", "银川"}, {"新疆维吾尔自治区", "乌鲁木齐"}
        };
        for (String[] row : rows) values.put(row[0], row[1]);
        return values;
    }

    private static Map<String, String> stations() {
        Map<String, String> values = new HashMap<>();
        String[][] rows = {
            {"北京", "北京南站"}, {"天津", "天津站"}, {"石家庄", "石家庄站"}, {"太原", "太原南站"},
            {"呼和浩特", "呼和浩特东站"}, {"沈阳", "沈阳北站"}, {"长春", "长春西站"}, {"哈尔滨", "哈尔滨西站"},
            {"上海", "上海虹桥站"}, {"南京", "南京南站"}, {"杭州", "杭州东站"}, {"合肥", "合肥南站"},
            {"福州", "福州南站"}, {"南昌", "南昌西站"}, {"济南", "济南西站"}, {"郑州", "郑州东站"},
            {"武汉", "武汉站"}, {"长沙", "长沙南站"}, {"广州", "广州南站"}, {"南宁", "南宁东站"},
            {"海口", "海口东站"}, {"重庆", "重庆北站"}, {"成都", "成都东站"}, {"贵阳", "贵阳北站"},
            {"昆明", "昆明南站"}, {"拉萨", "拉萨站"}, {"西安", "西安北站"}, {"兰州", "兰州西站"},
            {"西宁", "西宁站"}, {"银川", "银川站"}, {"乌鲁木齐", "乌鲁木齐站"},
            {"徐州", "徐州东站"}, {"温州", "温州南站"}, {"厦门", "厦门北站"}, {"青岛", "青岛北站"},
            {"洛阳", "洛阳龙门站"}, {"开封", "开封北站"}, {"安阳", "安阳东站"}, {"新乡", "新乡东站"},
            {"南阳", "南阳东站"}, {"襄阳", "襄阳东站"}, {"宜昌", "宜昌东站"}, {"岳阳", "岳阳东站"},
            {"衡阳", "衡阳东站"}, {"株洲", "株洲西站"}, {"深圳", "深圳北站"}, {"佛山", "佛山西站"},
            {"东莞", "虎门站"}, {"惠州", "惠州北站"}, {"湛江", "湛江西站"}, {"桂林", "桂林北站"},
            {"三亚", "三亚站"}, {"宜宾", "宜宾西站"}, {"宝鸡", "宝鸡南站"}, {"天水", "天水南站"}
        };
        for (String[] row : rows) values.put(row[0], row[1]);
        return values;
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, StandardCharsets.UTF_8.name()); }
        catch (Exception ignored) { return value; }
    }

    private TransportService() { }
}
