package cn.xiangyu.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Builds city-scoped food and culture lists from Baidu Baike entries. */
final class CityContentService {
    interface Callback { void onResult(Result result); }

    static final class Result {
        final List<LocalData.Item> food;
        final List<LocalData.Item> culture;
        final boolean fresh;

        Result(List<LocalData.Item> food, List<LocalData.Item> culture, boolean fresh) {
            this.food = food;
            this.culture = culture;
            this.fresh = fresh;
        }
    }

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final long CACHE_LIFE = 7L * 24 * 60 * 60 * 1000;
    private static final int CACHE_VERSION = 2;

    static void fetch(Context context, CityRepository.City city, Callback callback) {
        EXECUTOR.execute(() -> {
            SharedPreferences cache = context.getSharedPreferences("city_content_cache", Context.MODE_PRIVATE);
            String prefix = "v" + CACHE_VERSION + "_" + city.code;
            long time = cache.getLong(prefix + "_time", 0);
            String cached = cache.getString(prefix + "_json", "");
            if (!cached.isEmpty() && System.currentTimeMillis() - time < CACHE_LIFE) {
                callback.onResult(parseCache(city, cached, false));
                return;
            }

            List<LocalData.Item> food = fetchCategory(city, 0);
            List<LocalData.Item> culture = fetchCategory(city, 1);
            Result result = new Result(food, culture, !food.isEmpty() || !culture.isEmpty());
            if (result.fresh) {
                cache.edit().putString(prefix + "_json", serialize(result))
                    .putLong(prefix + "_time", System.currentTimeMillis()).apply();
            } else if (!cached.isEmpty()) {
                result = parseCache(city, cached, false);
            }
            callback.onResult(result);
        });
    }

    private static List<LocalData.Item> fetchCategory(CityRepository.City city, int category) {
        String[] suffixes = category == 0
            ? new String[]{"特色小吃", "传统美食", "地方名吃", "老字号美食", "地方特产", "传统名菜", "饮食文化"}
            : new String[]{"民俗", "非物质文化遗产", "传统技艺", "地方戏曲", "传统节庆", "传统习俗", "民间艺术"};
        List<LocalData.Item> result = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (String suffix : suffixes) {
            List<BaikeService.Entry> entries = BaikeService.suggest(city.name + suffix);
            for (BaikeService.Entry entry : entries) {
                if (!accepted(entry, city, category) || !used.add(entry.title)) continue;
                String detail = entry.description.isEmpty()
                    ? completeFallback(entry.title, city.name, category) : completeDescription(entry, city, category);
                result.add(new LocalData.Item(city.code + "-baike-" + category + "-" + result.size(),
                    entry.title, detail, "百度百科 · " + city.officialName + "专属条目",
                    category == 0 ? 0xffc4633f : 0xff537263, category == 0 ? "食" : "俗"));
                if (result.size() >= 6) return result;
            }
        }
        return result;
    }

    private static boolean accepted(BaikeService.Entry entry, CityRepository.City city, int category) {
        if (entry.title.isEmpty()) return false;
        String combined = entry.title + entry.description;
        if (combined.contains("图书") || combined.contains("电视剧") || combined.contains("电影")
                || combined.contains("歌曲") || combined.contains("公司") || combined.contains("学校")) return false;
        if (category == 0) {
            return combined.contains(city.name) || combined.contains(city.province.replace("省", ""))
                || combined.contains("小吃") || combined.contains("名吃") || combined.contains("菜肴")
                || combined.contains("食品") || combined.contains("美食");
        }
        return combined.contains(city.name) || combined.contains(city.province.replace("省", ""))
            || combined.contains("民俗") || combined.contains("非物质文化遗产")
            || combined.contains("传统技艺") || combined.contains("戏曲") || combined.contains("习俗");
    }

    private static String completeDescription(BaikeService.Entry entry, CityRepository.City city, int category) {
        if (category == 0) {
            return entry.description + "。点开后可继续查看原料、做法、口感和当地食用场景；具体店铺通过美团与小红书核对。";
        }
        return entry.description + "。点开后可继续了解历史背景、表现形式、传承方式和体验礼仪。";
    }

    private static String completeFallback(String title, String city, int category) {
        if (category == 0) {
            return title + "是百度百科联想到的" + city + "地方风味条目，可从原料、做法、口感和当地食用场景继续了解。";
        }
        return title + "是百度百科联想到的" + city + "文化条目，可从历史背景、传承方式和体验礼仪继续了解。";
    }

    private static String serialize(Result result) {
        JSONObject root = new JSONObject();
        try {
            root.put("food", serializeItems(result.food));
            root.put("culture", serializeItems(result.culture));
        } catch (Exception ignored) { }
        return root.toString();
    }

    private static JSONArray serializeItems(List<LocalData.Item> items) throws Exception {
        JSONArray array = new JSONArray();
        for (LocalData.Item item : items) {
            JSONObject value = new JSONObject();
            value.put("title", item.title);
            value.put("subtitle", item.subtitle);
            array.put(value);
        }
        return array;
    }

    private static Result parseCache(CityRepository.City city, String json, boolean fresh) {
        try {
            JSONObject root = new JSONObject(json);
            return new Result(parseItems(city, root.optJSONArray("food"), 0),
                parseItems(city, root.optJSONArray("culture"), 1), fresh);
        } catch (Exception ignored) {
            return new Result(new ArrayList<>(), new ArrayList<>(), false);
        }
    }

    private static List<LocalData.Item> parseItems(CityRepository.City city, JSONArray array, int category) {
        List<LocalData.Item> result = new ArrayList<>();
        if (array == null) return result;
        for (int i = 0; i < array.length(); i++) {
            JSONObject value = array.optJSONObject(i);
            if (value == null) continue;
            result.add(new LocalData.Item(city.code + "-baike-" + category + "-" + i,
                value.optString("title"), value.optString("subtitle"),
                "百度百科 · 7天缓存", category == 0 ? 0xffc4633f : 0xff537263,
                category == 0 ? "食" : "俗"));
        }
        return result;
    }

    private CityContentService() { }
}
