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

/** Supplies city-specific destinations from Baidu Baike only. */
final class DestinationService {
    interface Callback { void onResult(Result result); }

    static final class Result {
        final List<LocalData.Item> sights;
        final List<LocalData.Item> tips;
        final List<LocalData.Item> hotels;
        final boolean fresh;

        Result(List<LocalData.Item> sights, List<LocalData.Item> tips,
               List<LocalData.Item> hotels, boolean fresh) {
            this.sights = sights;
            this.tips = tips;
            this.hotels = hotels;
            this.fresh = fresh;
        }
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final long CACHE_LIFE = 7L * 24 * 60 * 60 * 1000;
    private static final int CACHE_VERSION = 7;

    static void fetch(Context context, CityRepository.City city, Callback callback) {
        EXECUTOR.execute(() -> {
            SharedPreferences cache = context.getSharedPreferences("destination_cache", Context.MODE_PRIVATE);
            String key = "v" + CACHE_VERSION + "_" + city.code;
            String cached = cache.getString(key + "_json", "");
            long updatedAt = cache.getLong(key + "_time", 0);
            if (!cached.isEmpty() && System.currentTimeMillis() - updatedAt < CACHE_LIFE) {
                callback.onResult(new Result(parse(city, cached), new ArrayList<>(), new ArrayList<>(), false));
                return;
            }

            List<LocalData.Item> sights = fetchSights(city);
            if (!sights.isEmpty()) {
                cache.edit().putString(key + "_json", serialize(sights))
                    .putLong(key + "_time", System.currentTimeMillis()).apply();
            } else if (!cached.isEmpty()) {
                sights = parse(city, cached);
            }
            callback.onResult(new Result(sights, new ArrayList<>(), new ArrayList<>(), !sights.isEmpty()));
        });
    }

    private static List<LocalData.Item> fetchSights(CityRepository.City city) {
        List<LocalData.Item> result = new ArrayList<>();
        Set<String> used = new HashSet<>();
        BaikeService.Entry cityCard = BaikeService.card(city.officialName);
        for (String title : cityCard.famousSights) {
            add(result, used, city, title,
                "百度百科城市词条收录的著名景点。点开可查看历史背景、核心看点、适合人群、建议时长及行前提醒。",
                scenicCategory(title));
            if (result.size() >= 12) return result;
        }

        String[] queries = {"旅游景点", "名胜古迹", "博物馆", "红色旅游", "亲子景点", "自然景区"};
        for (String suffix : queries) {
            for (BaikeService.Entry entry : BaikeService.suggest(city.name + suffix)) {
                if (!accepted(entry, city)) continue;
                String detail = entry.description.isEmpty()
                    ? "百度百科联想到的" + city.name + "目的地条目。点开查看完整词条，并在出发前核对预约、开放和交通。"
                    : entry.description + "。点开可查看完整百科介绍；开放时间、预约、门票和交通以景区最新公告为准。";
                add(result, used, city, entry.title, detail, scenicCategory(entry.title + entry.description));
                if (result.size() >= 12) return result;
            }
        }
        return result;
    }

    private static boolean accepted(BaikeService.Entry entry, CityRepository.City city) {
        String value = entry.title + entry.description;
        if (entry.title.length() < 2 || entry.title.length() > 32) return false;
        if (value.contains("图书") || value.contains("歌曲") || value.contains("电视剧")
                || value.contains("电影") || value.contains("公司")) return false;
        return value.contains(city.name) || value.contains(city.province.replace("省", ""))
            || value.contains("景区") || value.contains("景点") || value.contains("公园")
            || value.contains("博物馆") || value.contains("遗址") || value.contains("纪念馆")
            || value.contains("山") || value.contains("湖") || value.contains("峡谷");
    }

    private static void add(List<LocalData.Item> result, Set<String> used, CityRepository.City city,
                            String title, String detail, String category) {
        if (!used.add(title)) return;
        String mark = category.equals("亲子遛娃") ? "亲" : category.equals("红色学习") ? "红"
            : category.equals("纯自然景观") ? "野" : "景";
        result.add(new LocalData.Item(city.code + "-baike-sight-" + result.size(), title, detail,
            category + " · 百度百科", 0xff47748a, mark));
    }

    private static String scenicCategory(String value) {
        if (value.contains("纪念") || value.contains("革命") || value.contains("烈士")
                || value.contains("起义") || value.contains("会址") || value.contains("旧址")) return "红色学习";
        if (value.contains("科技馆") || value.contains("儿童") || value.contains("动物园")
                || value.contains("海洋馆") || value.contains("乐园")) return "亲子遛娃";
        if (value.contains("森林") || value.contains("湿地") || value.contains("峡谷")
                || value.contains("瀑布") || value.contains("草原") || value.contains("自然保护")
                || value.contains("地质") || value.contains("山") || value.contains("湖")) return "纯自然景观";
        return "人文与城市漫游";
    }

    private static String serialize(List<LocalData.Item> items) {
        JSONArray array = new JSONArray();
        try {
            for (LocalData.Item item : items) {
                JSONObject value = new JSONObject();
                value.put("title", item.title);
                value.put("subtitle", item.subtitle);
                value.put("meta", item.meta);
                value.put("mark", item.mark);
                array.put(value);
            }
        } catch (Exception ignored) { }
        return array.toString();
    }

    private static List<LocalData.Item> parse(CityRepository.City city, String json) {
        List<LocalData.Item> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject value = array.optJSONObject(i);
                if (value == null) continue;
                result.add(new LocalData.Item(city.code + "-baike-sight-" + i,
                    value.optString("title"), value.optString("subtitle"),
                    value.optString("meta", "百度百科 · 7天缓存"), 0xff47748a,
                    value.optString("mark", "景")));
            }
        } catch (Exception ignored) { }
        return result;
    }

    private DestinationService() { }
}
