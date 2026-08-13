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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class NearbyService {
    interface Callback { void onResult(Result result); }

    static final class Result {
        final List<String> food;
        final List<String> hotels;
        final List<String> transport;
        final boolean fresh;

        Result(List<String> food, List<String> hotels, List<String> transport, boolean fresh) {
            this.food = food;
            this.hotels = hotels;
            this.transport = transport;
            this.fresh = fresh;
        }
    }

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);

    static void fetch(double lat, double lon, Callback callback) {
        EXECUTOR.execute(() -> callback.onResult(query(lat, lon)));
    }

    private static Result query(double lat, double lon) {
        List<String> food = new ArrayList<>();
        List<String> hotels = new ArrayList<>();
        List<String> otherHotels = new ArrayList<>();
        List<String> upscaleHotels = new ArrayList<>();
        List<String> transport = new ArrayList<>();
        try {
            String around = "(around:5000," + lat + "," + lon + ")";
            String query = "[out:json][timeout:12];("
                + "nwr" + around + "[amenity~\"restaurant|fast_food|cafe|food_court\"][name];"
                + "nwr" + around + "[tourism~\"hotel|hostel|guest_house|motel\"][name];"
                + "nwr" + around + "[railway~\"station|halt|subway_entrance\"][name];"
                + "nwr" + around + "[amenity=bus_station][name];"
                + "nwr" + around + "[public_transport~\"station|stop_position\"][name];"
                + ");out tags center 80;";
            String endpoint = "https://overpass-api.de/api/interpreter?data="
                + URLEncoder.encode(query, StandardCharsets.UTF_8.name());
            HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setConnectTimeout(7000); connection.setReadTimeout(12000);
            connection.setRequestProperty("User-Agent", "XiangYuAndroid/1.3 (nearby guide client)");
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = reader.readLine()) != null) body.append(line);
            }
            JSONArray elements = new JSONObject(body.toString()).optJSONArray("elements");
            if (elements == null) return new Result(food, hotels, transport, false);
            for (int i = 0; i < elements.length(); i++) {
                JSONObject element = elements.getJSONObject(i);
                JSONObject tags = element.optJSONObject("tags");
                if (tags == null) continue;
                String name = tags.optString("name:zh", tags.optString("name", "")).trim();
                if (name.isEmpty()) continue;
                JSONObject center = element.optJSONObject("center");
                double itemLat = element.has("lat") ? element.optDouble("lat") : center == null ? Double.NaN : center.optDouble("lat", Double.NaN);
                double itemLon = element.has("lon") ? element.optDouble("lon") : center == null ? Double.NaN : center.optDouble("lon", Double.NaN);
                String value = name + distanceLabel(lat, lon, itemLat, itemLon);
                if (tags.has("tourism")) {
                    String tourism = tags.optString("tourism", "hotel");
                    String stars = tags.optString("stars", "");
                    if (isUpscaleHotel(name, stars)) addUnique(upscaleHotels, value, 20);
                    else if (isBudgetHotel(name, tourism)) addUnique(hotels, value + " · 平价优先", 5);
                    else addUnique(otherHotels, value, 20);
                }
                else if (tags.has("amenity") && !"bus_station".equals(tags.optString("amenity"))) addUnique(food, value, 5);
                else addUnique(transport, value, 5);
            }
            appendUnique(hotels, otherHotels, 5);
            appendUnique(hotels, upscaleHotels, 5);
            return new Result(food, hotels, transport, true);
        } catch (Exception ignored) {
            return new Result(food, hotels, transport, false);
        }
    }

    private static void addUnique(List<String> target, String value, int max) {
        if (target.size() >= max) return;
        for (String existing : target) if (existing.substring(0, existing.indexOf(" · ")).equals(value.substring(0, value.indexOf(" · ")))) return;
        target.add(value);
    }

    private static void appendUnique(List<String> target, List<String> source, int max) {
        for (String value : source) addUnique(target, value, max);
    }

    private static boolean isBudgetHotel(String name, String tourism) {
        String value = name.toLowerCase(Locale.ROOT);
        return "hostel".equals(tourism) || "guest_house".equals(tourism) || "motel".equals(tourism)
            || value.contains("青旅") || value.contains("青年旅舍") || value.contains("旅馆")
            || value.contains("旅店") || value.contains("客栈") || value.contains("民宿")
            || value.contains("快捷") || value.contains("经济型") || value.contains("招待所");
    }

    private static boolean isUpscaleHotel(String name, String stars) {
        try {
            String first = stars.replaceAll("[^0-9.].*$", "");
            if (!first.isEmpty() && Double.parseDouble(first) >= 3) return true;
        } catch (Exception ignored) { }
        String value = name.toLowerCase(Locale.ROOT);
        return value.contains("五星") || value.contains("四星") || value.contains("三星")
            || value.contains("丽思卡尔顿") || value.contains("华尔道夫") || value.contains("四季酒店")
            || value.contains("瑞吉") || value.contains("柏悦") || value.contains("君悦")
            || value.contains("洲际") || value.contains("香格里拉") || value.contains("文华东方");
    }

    private static String distanceLabel(double lat1, double lon1, double lat2, double lon2) {
        if (Double.isNaN(lat2) || Double.isNaN(lon2)) return " · 距离待核实";
        double earth = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double km = earth * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return km < 1 ? " · 约" + Math.max(50, Math.round(km * 1000 / 50) * 50) + "米"
            : " · 约" + String.format(Locale.CHINA, "%.1f", km) + "公里";
    }

    private NearbyService() { }
}
