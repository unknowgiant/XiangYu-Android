package cn.xiangyu.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PhotoService {
    interface Callback { void onResult(List<Photo> photos); }

    static final class Photo {
        final String title;
        final String source;
        final Bitmap bitmap;

        Photo(String title, String source, Bitmap bitmap) {
            this.title = title;
            this.source = source;
            this.bitmap = bitmap;
        }
    }

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3);
    private static final Map<String, List<Photo>> MEMORY_CACHE = new LinkedHashMap<>();

    static void fetch(CityRepository.City city, List<LocalData.Item> sights, Callback callback) {
        String cacheKey = homeCacheKey(city, sights);
        synchronized (MEMORY_CACHE) {
            List<Photo> cached = MEMORY_CACHE.get(cacheKey);
            if (cached != null) { callback.onResult(new ArrayList<>(cached)); return; }
        }
        EXECUTOR.execute(() -> {
            List<Photo> photos = load(city, sights, callback);
            synchronized (MEMORY_CACHE) { MEMORY_CACHE.put(cacheKey, new ArrayList<>(photos)); }
            callback.onResult(new ArrayList<>(photos));
        });
    }

    private static String homeCacheKey(CityRepository.City city, List<LocalData.Item> sights) {
        StringBuilder value = new StringBuilder("home:v3:").append(city.code);
        int count = 0;
        for (LocalData.Item sight : sights) {
            if (count++ >= 7) break;
            value.append(':').append(sight.title);
        }
        return value.toString();
    }

    static void fetchItem(String cityCode, String cityName, LocalData.Item item, int category, Callback callback) {
        String key = "item:" + cityCode + ":" + category + ":" + item.title;
        synchronized (MEMORY_CACHE) {
            List<Photo> cached = MEMORY_CACHE.get(key);
            if (cached != null) { callback.onResult(new ArrayList<>(cached)); return; }
        }
        EXECUTOR.execute(() -> {
            List<Photo> photos = loadItem(cityName, item.title, category);
            synchronized (MEMORY_CACHE) {
                if (MEMORY_CACHE.size() >= 14) {
                    String oldest = MEMORY_CACHE.keySet().iterator().next();
                    MEMORY_CACHE.remove(oldest);
                }
                MEMORY_CACHE.put(key, new ArrayList<>(photos));
            }
            callback.onResult(new ArrayList<>(photos));
        });
    }

    private static List<Photo> loadItem(String city, String title, int category) {
        List<Photo> result = new ArrayList<>();
        String type = category == 0 ? "地方美食" : category == 1 ? "民俗文化"
            : category == 2 ? "旅游景点" : category == 3 ? "旅游避坑 景区现场" : "住宿酒店";
        String query = city + " " + title + " " + type;
        try {
            String endpoint = "https://cn.bing.com/images/search?q="
                + URLEncoder.encode(query + " 实景 实拍", StandardCharsets.UTF_8.name()) + "&form=HDRSC2";
            String html = requestText(endpoint, 4000, 6500);
            Pattern pattern = Pattern.compile("&quot;purl&quot;:&quot;(.*?)&quot;.*?&quot;murl&quot;:&quot;(.*?)&quot;");
            Matcher matcher = pattern.matcher(html);
            while (matcher.find()) {
                String pageUrl = decodeHtml(matcher.group(1));
                String imageUrl = decodeHtml(matcher.group(2));
                if (!imageUrl.startsWith("https://")) continue;
                Bitmap bitmap = downloadBitmap(imageUrl);
                if (bitmap == null || bitmap.getWidth() < 400 || bitmap.getHeight() < 220) continue;
                result.add(new Photo(title, sourceHost(pageUrl) + " · 互联网原图", bitmap));
                break;
            }
        } catch (Exception ignored) { }
        if (!result.isEmpty()) return result;
        result.addAll(loadBaiduImages(query + " 实景 实拍", title, 1));
        if (!result.isEmpty()) return result;
        try {
            List<JSONObject> pages = searchCommonsItem(query);
            for (JSONObject page : pages) {
                JSONArray info = page.optJSONArray("imageinfo");
                if (info == null || info.length() == 0) continue;
                String url = info.getJSONObject(0).optString("thumburl", "");
                Bitmap bitmap = downloadBitmap(url);
                if (bitmap == null || bitmap.getWidth() < 320 || bitmap.getHeight() < 180) continue;
                result.add(new Photo(title, "Wikimedia Commons · 备用图片", bitmap));
                return result;
            }
        } catch (Exception ignored) { }
        return result;
    }

    private static List<Photo> load(CityRepository.City city, List<LocalData.Item> sights, Callback callback) {
        List<Photo> result = new ArrayList<>();
        Set<String> usedUrls = new HashSet<>();
        Set<Long> usedSignatures = new HashSet<>();
        List<String> landmarkNames = new ArrayList<>();
        for (LocalData.Item sight : sights) {
            if (landmarkNames.size() >= 5) break;
            if (isGenericSight(sight.title) || sight.meta.contains("联网补充具体地点")
                    || sight.meta.contains("周边景观")) continue;
            landmarkNames.add(sight.title);
        }
        result.addAll(loadBingFallback(city, landmarkNames, callback));
        if (result.isEmpty()) {
            for (String landmark : landmarkNames) {
                if (result.size() >= 5) break;
                List<Photo> baidu = loadBaiduImages(city.name + " " + landmark + " 景点 实景", landmark, 1);
                if (!baidu.isEmpty()) {
                    Photo candidate = baidu.get(0);
                    long signature = imageSignature(candidate.bitmap);
                    if (isNearDuplicate(signature, usedSignatures)) continue;
                    usedSignatures.add(signature);
                    result.add(candidate);
                    callback.onResult(new ArrayList<>(result));
                }
            }
        }
        if (result.isEmpty()) {
            try {
                List<JSONObject> pages = searchCommons(city.name, landmarkNames);
                for (JSONObject page : pages) {
                    if (result.size() >= 5) break;
                    JSONArray info = page.optJSONArray("imageinfo");
                    if (info == null || info.length() == 0) continue;
                    String url = info.getJSONObject(0).optString("thumburl", "");
                    if (url.isEmpty() || !usedUrls.add(url)) continue;
                    Bitmap bitmap = downloadBitmap(url);
                    if (bitmap == null) continue;
                    long signature = imageSignature(bitmap);
                    if (isNearDuplicate(signature, usedSignatures)) continue;
                    usedSignatures.add(signature);
                    result.add(new Photo(bestTitle(page.optString("title", ""), city.name, landmarkNames),
                        "Wikimedia Commons · 备用图片", bitmap));
                    callback.onResult(new ArrayList<>(result));
                }
            } catch (Exception ignored) { }
        }
        return result;
    }

    private static List<Photo> loadBingFallback(CityRepository.City city, List<String> landmarks, Callback callback) {
        List<Photo> result = new ArrayList<>();
        Set<String> used = new HashSet<>();
        Set<Long> signatures = new HashSet<>();
        List<String> targets = landmarks.isEmpty()
            ? java.util.Collections.singletonList(city.name + "代表性景区") : landmarks;
        for (String landmark : targets) {
            if (result.size() >= 5) break;
            try {
                String query = city.officialName + " " + landmark + " 景区 官方 实景";
                String endpoint = "https://cn.bing.com/images/search?q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8.name()) + "&form=HDRSC2";
                String html = requestText(endpoint, 4000, 6000);
                Pattern pattern = Pattern.compile("&quot;purl&quot;:&quot;(.*?)&quot;.*?&quot;murl&quot;:&quot;(.*?)&quot;");
                Matcher matcher = pattern.matcher(html);
                while (matcher.find()) {
                    String pageUrl = decodeHtml(matcher.group(1));
                    String imageUrl = decodeHtml(matcher.group(2));
                    if (!imageUrl.startsWith("https://") || !used.add(imageUrl)) continue;
                    Bitmap bitmap = downloadBitmap(imageUrl);
                    if (bitmap == null || bitmap.getWidth() < 400 || bitmap.getHeight() < 240) continue;
                    long signature = imageSignature(bitmap);
                    if (isNearDuplicate(signature, signatures)) continue;
                    signatures.add(signature);
                    result.add(new Photo(city.name + " · " + landmark,
                        sourceHost(pageUrl) + " · " + city.name + "检索", bitmap));
                    callback.onResult(new ArrayList<>(result));
                    break;
                }
            } catch (Exception ignored) { }
        }
        return result;
    }

    private static boolean isGenericSight(String title) {
        String[] generic = {"城市风貌", "自然与郊野景观", "博物馆与文化场馆", "老城与历史街区",
            "城市公园与滨水空间", "所辖县区目的地", "地方博物馆", "周边县域", "周边景观",
            "亲子科普与遛娃去处", "红色记忆与纪念场馆", "小众自然景观"};
        for (String value : generic) if (title.contains(value)) return true;
        return false;
    }

    private static long imageSignature(Bitmap bitmap) {
        Bitmap sample = Bitmap.createScaledBitmap(bitmap, 9, 8, true);
        long signature = 0L;
        int bit = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int left = sample.getPixel(x, y);
                int right = sample.getPixel(x + 1, y);
                int leftLight = ((left >> 16) & 0xff) * 30 + ((left >> 8) & 0xff) * 59 + (left & 0xff) * 11;
                int rightLight = ((right >> 16) & 0xff) * 30 + ((right >> 8) & 0xff) * 59 + (right & 0xff) * 11;
                if (leftLight > rightLight) signature |= 1L << bit;
                bit++;
            }
        }
        if (sample != bitmap) sample.recycle();
        return signature;
    }

    private static boolean isNearDuplicate(long candidate, Set<Long> existing) {
        for (long value : existing) if (Long.bitCount(candidate ^ value) <= 7) return true;
        return false;
    }

    private static List<Photo> loadBaiduImages(String query, String title, int limit) {
        List<Photo> result = new ArrayList<>();
        Set<String> used = new HashSet<>();
        try {
            String endpoint = "https://image.baidu.com/search/index?tn=baiduimage&word="
                + URLEncoder.encode(query, StandardCharsets.UTF_8.name());
            String html = requestText(endpoint, 4000, 6000);
            Pattern pattern = Pattern.compile("(?:thumbURL|middleURL|objURL)\\\"?\\s*[:=]\\s*\\\"(https?:\\\\?/\\\\?/[^\\\"]+)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(html);
            while (matcher.find() && result.size() < limit) {
                String imageUrl = decodeJsonUrl(matcher.group(1));
                if (!used.add(imageUrl)) continue;
                Bitmap bitmap = downloadBitmap(imageUrl);
                if (bitmap == null || bitmap.getWidth() < 400 || bitmap.getHeight() < 220) continue;
                result.add(new Photo(title, "百度图片 · 互联网原图", bitmap));
            }
        } catch (Exception ignored) { }
        return result;
    }

    private static String requestText(String endpoint, int connectTimeout, int readTimeout) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(connectTimeout); connection.setReadTimeout(readTimeout);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) XiangYu/1.4");
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line; while ((line = reader.readLine()) != null) body.append(line);
        }
        return body.toString();
    }

    private static String decodeHtml(String value) {
        return value.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"");
    }

    private static String decodeJsonUrl(String value) {
        return decodeHtml(value).replace("\\/", "/").replace("\\u002F", "/").replace("\\u0026", "&");
    }

    private static String sourceHost(String pageUrl) {
        try {
            String host = new URI(pageUrl).getHost();
            return host == null || host.isEmpty() ? "互联网图片来源" : host.replaceFirst("^www\\.", "");
        } catch (Exception ignored) { return "互联网图片来源"; }
    }

    private static List<JSONObject> searchCommons(String city, List<String> landmarks) throws Exception {
        StringBuilder search = new StringBuilder(city).append(" (");
        for (int i = 0; i < landmarks.size(); i++) {
            if (i > 0) search.append(" OR ");
            search.append('"').append(landmarks.get(i)).append('"');
        }
        search.append(") filetype:bitmap");
        String endpoint = "https://commons.wikimedia.org/w/api.php?action=query&generator=search"
            + "&gsrnamespace=6&gsrlimit=15&gsrsearch=" + URLEncoder.encode(search.toString(), StandardCharsets.UTF_8.name())
            + "&prop=imageinfo&iiprop=url&iiurlwidth=900&format=json&formatversion=2&origin=*";
        JSONObject root = requestJson(endpoint);
        JSONArray pages = root.optJSONObject("query") == null ? null : root.optJSONObject("query").optJSONArray("pages");
        List<JSONObject> result = new ArrayList<>();
        if (pages == null) return result;
        for (int i = 0; i < pages.length(); i++) {
            JSONObject page = pages.getJSONObject(i);
            String title = page.optString("title", "").toLowerCase();
            if (title.endsWith(".webm") || title.endsWith(".ogv") || title.endsWith(".pdf") || title.endsWith(".djvu")) continue;
            JSONArray info = page.optJSONArray("imageinfo");
            if (info == null || info.length() == 0) continue;
            if (!info.getJSONObject(0).optString("thumburl", "").isEmpty()) result.add(page);
        }
        return result;
    }

    private static List<JSONObject> searchCommonsItem(String search) throws Exception {
        String endpoint = "https://commons.wikimedia.org/w/api.php?action=query&generator=search"
            + "&gsrnamespace=6&gsrlimit=8&gsrsearch="
            + URLEncoder.encode(search + " filetype:bitmap", StandardCharsets.UTF_8.name())
            + "&prop=imageinfo&iiprop=url&iiurlwidth=900&format=json&formatversion=2&origin=*";
        JSONObject root = requestJson(endpoint);
        JSONArray pages = root.optJSONObject("query") == null ? null : root.optJSONObject("query").optJSONArray("pages");
        List<JSONObject> result = new ArrayList<>();
        if (pages == null) return result;
        for (int i = 0; i < pages.length(); i++) {
            JSONObject page = pages.getJSONObject(i);
            String title = page.optString("title", "").toLowerCase();
            if (title.endsWith(".webm") || title.endsWith(".ogv") || title.endsWith(".pdf") || title.endsWith(".djvu")) continue;
            JSONArray info = page.optJSONArray("imageinfo");
            if (info != null && info.length() > 0 && !info.getJSONObject(0).optString("thumburl", "").isEmpty()) result.add(page);
        }
        return result;
    }

    private static String bestTitle(String fileTitle, String city, List<String> landmarks) {
        String value = fileTitle.toLowerCase();
        for (String landmark : landmarks) if (value.contains(landmark.toLowerCase())) return landmark;
        String cleaned = fileTitle.startsWith("File:") ? fileTitle.substring(5) : fileTitle;
        int dot = cleaned.lastIndexOf('.');
        if (dot > 0) cleaned = cleaned.substring(0, dot);
        cleaned = cleaned.replace('_', ' ');
        return cleaned.isEmpty() ? city + "景点影像" : cleaned;
    }

    private static JSONObject requestJson(String endpoint) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(3500); connection.setReadTimeout(5000);
        connection.setRequestProperty("User-Agent", "XiangYuAndroid/1.4 (local landmark carousel)");
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line; while ((line = reader.readLine()) != null) body.append(line);
        }
        return new JSONObject(body.toString());
    }

    private static Bitmap downloadBitmap(String source) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(source).openConnection();
            connection.setConnectTimeout(5000); connection.setReadTimeout(7000);
            connection.setRequestProperty("User-Agent", "XiangYuAndroid/1.4 (image client)");
            byte[] data;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read; int total = 0;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > 12 * 1024 * 1024) return null;
                    output.write(buffer, 0, read);
                }
                data = output.toByteArray();
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            int sample = 1;
            while (bounds.outWidth / sample > 1400 || bounds.outHeight / sample > 1000) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (Exception ignored) { return null; }
    }

    private PhotoService() { }
}
