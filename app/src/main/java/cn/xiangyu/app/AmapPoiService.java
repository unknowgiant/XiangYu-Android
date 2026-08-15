package cn.xiangyu.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Official Amap Web Service client. The key is supplied only by untracked local.properties. */
final class AmapPoiService {
    static final String DINING_TYPES = "050000";
    static final String LODGING_TYPES = "100000";
    static final String TRANSPORT_TYPES = "150000";

    static final class Poi {
        final String name;
        final String address;
        final String type;
        final String rating;
        final String cost;
        final int distanceMeters;

        Poi(String name, String address, String type, String rating, String cost, int distanceMeters) {
            this.name = name;
            this.address = address;
            this.type = type;
            this.rating = rating;
            this.cost = cost;
            this.distanceMeters = distanceMeters;
        }

        String detail(boolean includeDistance) {
            StringBuilder value = new StringBuilder(name);
            List<String> facts = new ArrayList<>();
            if (includeDistance && distanceMeters > 0) {
                facts.add(distanceMeters < 1000 ? distanceMeters + " 米"
                    : String.format(java.util.Locale.CHINA, "%.1f 公里", distanceMeters / 1000d));
            }
            if (!rating.isEmpty()) facts.add("评分 " + rating);
            if (!cost.isEmpty()) facts.add("人均约 ¥" + normalizeCost(cost));
            if (!facts.isEmpty()) value.append("\n").append(String.join(" · ", facts));
            if (!address.isEmpty()) value.append("\n").append(address);
            return value.toString();
        }
    }

    static boolean configured() {
        return BuildConfig.AMAP_WEB_KEY != null && !BuildConfig.AMAP_WEB_KEY.trim().isEmpty();
    }

    static List<Poi> search(CityRepository.City city, String keyword, String types, int limit) {
        if (!configured()) return new ArrayList<>();
        String endpoint = "https://restapi.amap.com/v3/place/text?key=" + encode(BuildConfig.AMAP_WEB_KEY)
            + "&keywords=" + encode(keyword)
            + "&types=" + encode(types)
            + "&city=" + encode(city.officialName)
            + "&citylimit=true&children=0&offset=" + Math.min(25, Math.max(limit, 10))
            + "&page=1&extensions=all";
        return requestPois(endpoint, limit);
    }

    static List<Poi> around(double lat, double lon, String types, int radiusMeters, int limit) {
        return around(lat, lon, "", types, radiusMeters, limit);
    }

    static List<Poi> around(double lat, double lon, String keyword, String types,
                            int radiusMeters, int limit) {
        if (!configured()) return new ArrayList<>();
        String location = String.format(java.util.Locale.US, "%.6f,%.6f", lon, lat);
        String endpoint = "https://restapi.amap.com/v3/place/around?key=" + encode(BuildConfig.AMAP_WEB_KEY)
            + "&location=" + location
            + "&keywords=" + encode(keyword)
            + "&types=" + encode(types)
            + "&radius=" + Math.min(50000, Math.max(100, radiusMeters))
            + "&sortrule=distance&offset=" + Math.min(25, Math.max(limit, 10))
            + "&page=1&extensions=all";
        return requestPois(endpoint, limit);
    }

    static boolean isLikelyUpscale(Poi poi) {
        String value = poi.name + poi.type;
        String[] tokens = {
            "五星", "四星", "三星", "豪华型", "高档型", "高档宾馆",
            "丽思卡尔顿", "华尔道夫", "四季酒店", "瑞吉", "柏悦", "君悦",
            "洲际", "香格里拉", "文华东方", "万豪", "希尔顿", "喜来登",
            "凯宾斯基", "威斯汀", "雅高瑞享", "索菲特", "康莱德", "瑰丽",
            "半岛酒店", "宝格丽", "安缦", "嘉佩乐", "艾迪逊", "皇冠假日"
        };
        for (String token : tokens) if (value.contains(token)) return true;
        return value.contains("W酒店") || value.contains("W 酒店");
    }

    static String searchUrl(String city, String keyword) {
        return "https://uri.amap.com/search?keyword=" + encode(keyword)
            + "&city=" + encode(city) + "&src=xiangyu&coordinate=gaode&callnative=0";
    }

    private static List<Poi> requestPois(String endpoint, int limit) {
        List<Poi> result = new ArrayList<>();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(7000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null && body.length() < 1_500_000) body.append(line);
                JSONObject root = new JSONObject(body.toString());
                if (!"1".equals(root.optString("status"))) return result;
                JSONArray pois = root.optJSONArray("pois");
                if (pois == null) return result;
                for (int i = 0; i < pois.length() && result.size() < limit; i++) {
                    JSONObject value = pois.optJSONObject(i);
                    if (value == null) continue;
                    String name = clean(value.optString("name"));
                    if (name.isEmpty() || !ContentSafety.isSafeTitle(name)) continue;
                    JSONObject business = value.optJSONObject("biz_ext");
                    String rating = scalar(business, "rating");
                    String cost = scalar(business, "cost");
                    result.add(new Poi(name, cleanJsonValue(value.opt("address")),
                        clean(value.optString("type")), rating, cost,
                        parseInteger(value.optString("distance"))));
                }
            }
        } catch (Exception ignored) {
            result.clear();
        } finally {
            if (connection != null) connection.disconnect();
        }
        return result;
    }

    private static String scalar(JSONObject value, String key) {
        if (value == null) return "";
        return cleanJsonValue(value.opt(key));
    }

    private static String cleanJsonValue(Object value) {
        if (value == null || value == JSONObject.NULL || value instanceof JSONArray
                || value instanceof JSONObject) return "";
        return clean(String.valueOf(value));
    }

    private static String clean(String value) {
        if (value == null || "[]".equals(value) || "null".equalsIgnoreCase(value)) return "";
        return value.replaceAll("\\s+", " ").trim();
    }

    private static int parseInteger(String value) {
        try { return (int) Math.round(Double.parseDouble(value)); }
        catch (Exception ignored) { return 0; }
    }

    private static String normalizeCost(String value) {
        try {
            double number = Double.parseDouble(value);
            return number == Math.rint(number) ? String.valueOf((int) number) : value;
        } catch (Exception ignored) {
            return value;
        }
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, StandardCharsets.UTF_8.name()); }
        catch (Exception ignored) { return value; }
    }

    private AmapPoiService() { }
}
