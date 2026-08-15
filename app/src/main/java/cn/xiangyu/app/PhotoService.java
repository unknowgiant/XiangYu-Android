package cn.xiangyu.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
        String key = "item:v2:" + cityCode + ":" + category + ":" + item.title;
        synchronized (MEMORY_CACHE) {
            List<Photo> cached = MEMORY_CACHE.get(key);
            if (cached != null) { callback.onResult(new ArrayList<>(cached)); return; }
        }
        EXECUTOR.execute(() -> {
            List<Photo> result = new ArrayList<>();
            addCardPhoto(result, item.title, item.title, new HashSet<>());
            if (result.isEmpty()) addCardPhoto(result, cityName + item.title, item.title, new HashSet<>());
            put(key, result);
            callback.onResult(new ArrayList<>(result));
        });
    }

    private static List<Photo> loadHome(CityRepository.City city, List<LocalData.Item> sights,
                                        Callback callback) {
        List<Photo> result = new ArrayList<>();
        Set<Long> signatures = new HashSet<>();
        Set<String> usedKeywords = new HashSet<>();
        for (LocalData.Item sight : sights) {
            if (result.size() >= 8) break;
            if (isGenericSight(sight.title) || !usedKeywords.add(sight.title)) continue;
            int before = result.size();
            addCardPhoto(result, sight.title, city.name + " · " + sight.title, signatures);
            if (result.size() > before) callback.onResult(new ArrayList<>(result));
        }

        if (result.size() < 8) {
            BaikeService.Entry cityEntry = BaikeService.card(city.officialName);
            for (String sight : cityEntry.famousSights) {
                if (result.size() >= 8) break;
                if (isGenericSight(sight) || !usedKeywords.add(sight)) continue;
                int before = result.size();
                addCardPhoto(result, sight, city.name + " · " + sight, signatures);
                if (result.size() > before) callback.onResult(new ArrayList<>(result));
            }
        }

        if (result.size() < 5) {
            for (BaikeService.Entry entry : BaikeService.suggest(city.name + " 景点")) {
                if (result.size() >= 8) break;
                if (entry.title.isEmpty() || isGenericSight(entry.title)
                        || !usedKeywords.add(entry.title)) continue;
                int before = result.size();
                addEntryPhoto(result, entry, city.name + " · " + entry.title, signatures);
                if (result.size() > before) callback.onResult(new ArrayList<>(result));
            }
        }

        if (result.size() < 5 && usedKeywords.add(city.officialName)) {
            int before = result.size();
            addCardPhoto(result, city.officialName, city.name, signatures);
            if (result.size() > before) callback.onResult(new ArrayList<>(result));
        }
        return result;
    }

    private static void addCardPhoto(List<Photo> result, String keyword, String title,
                                     Set<Long> signatures) {
        addEntryPhoto(result, BaikeService.card(keyword), title, signatures);
    }

    private static void addEntryPhoto(List<Photo> result, BaikeService.Entry entry, String title,
                                      Set<Long> signatures) {
        if (entry.imageUrl.isEmpty()) return;
        Bitmap bitmap = downloadBitmap(entry.imageUrl);
        if (bitmap == null || bitmap.getWidth() < 240 || bitmap.getHeight() < 160) return;
        long signature = imageSignature(bitmap);
        if (isNearDuplicate(signature, signatures)) return;
        signatures.add(signature);
        result.add(new Photo(title, "百度百科 · 词条图片", bitmap));
    }

    private static String homeCacheKey(CityRepository.City city, List<LocalData.Item> sights) {
        StringBuilder value = new StringBuilder("home:v5:").append(city.code);
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

    private static Bitmap downloadBitmap(String source) {
        try {
            URL url = new URL(source);
            if (!"https".equalsIgnoreCase(url.getProtocol()) || !isTrustedImageHost(url.getHost())) return null;
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Referer", "https://baike.baidu.com/");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) XiangYu/1.9.0");
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
        String value = host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
        return value.equals("baike.baidu.com") || value.endsWith(".baidu.com")
            || value.endsWith(".bdimg.com") || value.endsWith(".bcebos.com");
    }

    private PhotoService() { }
}
