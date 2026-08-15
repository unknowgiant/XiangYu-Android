package cn.xiangyu.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private static final Pattern BING_METADATA = Pattern.compile("\\bm=\"([^\"]+)\"");

    private static final class SearchImage {
        final String thumbnailUrl;

        SearchImage(String thumbnailUrl) {
            this.thumbnailUrl = thumbnailUrl;
        }
    }

    static void fetch(CityRepository.City city, List<LocalData.Item> sights, Callback callback) {
        String key = homeCacheKey(city, sights);
        synchronized (MEMORY_CACHE) {
            List<Photo> cached = MEMORY_CACHE.get(key);
            if (cached != null) { callback.onResult(new ArrayList<>(cached)); return; }
        }
        EXECUTOR.execute(() -> {
            List<Photo> photos = loadHome(city, sights, callback);
            put(key, photos);
            callback.onResult(new ArrayList<>(photos));
        });
    }

    static void fetchItem(String cityCode, String cityName, LocalData.Item item,
                          int category, Callback callback) {
        String key = "item:v3:" + cityCode + ":" + category + ":" + item.title;
        synchronized (MEMORY_CACHE) {
            List<Photo> cached = MEMORY_CACHE.get(key);
            if (cached != null) { callback.onResult(new ArrayList<>(cached)); return; }
        }
        EXECUTOR.execute(() -> {
            List<Photo> result = new ArrayList<>();
            addSearchPhoto(result, cityName + " " + item.title + " 实拍", item.title,
                new HashSet<>(), 1);
            put(key, result);
            callback.onResult(new ArrayList<>(result));
        });
    }

    private static List<Photo> loadHome(CityRepository.City city, List<LocalData.Item> sights,
                                        Callback callback) {
        List<Photo> result = new ArrayList<>();
        Set<Long> signatures = new HashSet<>();
        Set<String> usedKeywords = new HashSet<>();
        List<String> searchKeywords = new ArrayList<>();
        for (LocalData.Item sight : sights) {
            if (searchKeywords.size() >= 8) break;
            if (sight.id.contains("-sight-more-") || isGenericSight(sight.title)
                    || !usedKeywords.add(sight.title)) continue;
            searchKeywords.add(sight.title);
        }

        if (searchKeywords.size() < 8) {
            BaikeService.Entry cityEntry = BaikeService.card(city.officialName);
            for (String sight : cityEntry.famousSights) {
                if (searchKeywords.size() >= 8) break;
                if (isGenericSight(sight) || !usedKeywords.add(sight)) continue;
                searchKeywords.add(sight);
            }
        }

        if (searchKeywords.size() < 5) {
            for (BaikeService.Entry entry : BaikeService.suggest(city.name + " 景点")) {
                if (searchKeywords.size() >= 8) break;
                if (entry.title.isEmpty() || isGenericSight(entry.title)
                        || !usedKeywords.add(entry.title)) continue;
                searchKeywords.add(entry.title);
            }
        }

        for (String sight : searchKeywords) {
            if (result.size() >= 8) break;
            int before = result.size();
            addSearchPhoto(result, city.name + " " + sight + " 游客实拍 旅行摄影",
                city.name + " · " + sight, signatures, 1);
            if (result.size() > before) callback.onResult(new ArrayList<>(result));
        }

        // A second distinct result for concrete sights fills short city lists without generic scenery.
        for (String sight : searchKeywords) {
            if (result.size() >= 5) break;
            int before = result.size();
            addSearchPhoto(result, city.name + " " + sight + " 游客实拍 旅行摄影",
                city.name + " · " + sight, signatures, 1);
            if (result.size() > before) callback.onResult(new ArrayList<>(result));
        }
        return result;
    }

    private static void addSearchPhoto(List<Photo> result, String query, String title,
                                       Set<Long> signatures, int limit) {
        int added = 0;
        for (SearchImage image : searchBingImages(query, 10)) {
            Bitmap bitmap = downloadBitmap(image.thumbnailUrl);
            if (bitmap == null || bitmap.getWidth() < 240 || bitmap.getHeight() < 160) continue;
            long signature = imageSignature(bitmap);
            if (isNearDuplicate(signature, signatures)) continue;
            signatures.add(signature);
            result.add(new Photo(title, "必应图片搜索 · 游客实拍检索", bitmap));
            if (++added >= limit) return;
        }
    }

    private static String homeCacheKey(CityRepository.City city, List<LocalData.Item> sights) {
        StringBuilder value = new StringBuilder("home:v6:").append(city.code);
        int count = 0;
        for (LocalData.Item sight : sights) {
            if (count++ >= 8) break;
            value.append(':').append(sight.title);
        }
        return value.toString();
    }

    private static void put(String key, List<Photo> photos) {
        synchronized (MEMORY_CACHE) {
            while (MEMORY_CACHE.size() >= 14) {
                MEMORY_CACHE.remove(MEMORY_CACHE.keySet().iterator().next());
            }
            MEMORY_CACHE.put(key, new ArrayList<>(photos));
        }
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

    private static List<SearchImage> searchBingImages(String query, int limit) {
        List<SearchImage> result = new ArrayList<>();
        try {
            String endpoint = "https://cn.bing.com/images/search?q=" + encode(query)
                + "&form=HDRSC2&qft=%2Bfilterui%3Aimagesize-large";
            HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            connection.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/132 Mobile Safari/537.36");
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && body.length() < 2_500_000) {
                    body.append(line);
                }
            } finally {
                connection.disconnect();
            }
            Matcher matcher = BING_METADATA.matcher(body);
            Set<String> used = new HashSet<>();
            while (matcher.find() && result.size() < limit) {
                JSONObject metadata = new JSONObject(htmlDecode(matcher.group(1)));
                String thumbnail = normalizeBingThumbnail(metadata.optString("turl"));
                String sourcePage = metadata.optString("purl");
                String original = metadata.optString("murl");
                if (!isBingImageUrl(thumbnail) || isBaiduSource(sourcePage)
                        || isBaiduSource(original) || !used.add(thumbnail)) continue;
                result.add(new SearchImage(thumbnail));
            }
        } catch (Exception ignored) { }
        return result;
    }

    private static Bitmap downloadBitmap(String source) {
        try {
            URL url = new URL(source);
            if (!"https".equalsIgnoreCase(url.getProtocol()) || !isTrustedImageHost(url.getHost())) return null;
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Referer", "https://cn.bing.com/");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) XiangYu/1.9.1");
            connection.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
            int status = connection.getResponseCode();
            String contentType = connection.getContentType();
            if (status < 200 || status >= 300 || contentType == null
                    || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
                connection.disconnect();
                return null;
            }
            byte[] data;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                int total = 0;
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
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isTrustedImageHost(String host) {
        String value = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return value.equals("bing.net") || value.endsWith(".bing.net")
            || value.equals("bing.com") || value.endsWith(".bing.com");
    }

    private static boolean isBingImageUrl(String source) {
        try {
            URL value = new URL(source);
            return "https".equalsIgnoreCase(value.getProtocol()) && isTrustedImageHost(value.getHost());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String normalizeBingThumbnail(String source) {
        try {
            URL value = new URL(source);
            if (!"https".equalsIgnoreCase(value.getProtocol())
                    || !isTrustedImageHost(value.getHost())) return "";
            return "https://cn.bing.com" + value.getFile();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean isBaiduSource(String source) {
        try {
            String host = new URL(source).getHost().toLowerCase(Locale.ROOT);
            return host.equals("baidu.com") || host.endsWith(".baidu.com")
                || host.equals("bdimg.com") || host.endsWith(".bdimg.com")
                || host.equals("bcebos.com") || host.endsWith(".bcebos.com");
        } catch (Exception ignored) {
            return source != null && source.toLowerCase(Locale.ROOT).contains("baidu");
        }
    }

    private static String htmlDecode(String value) {
        return value.replace("&quot;", "\"").replace("&#34;", "\"")
            .replace("&amp;", "&").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">");
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, StandardCharsets.UTF_8.name()); }
        catch (Exception ignored) { return value; }
    }

    private PhotoService() { }
}
