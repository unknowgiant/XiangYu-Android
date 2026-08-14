package cn.xiangyu.app;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class KnowledgeService {
    interface Callback { void onResult(Result result); }

    static final class Result {
        final String introduction;
        final String source;
        final String baikeUrl;

        Result(String introduction, String source, String baikeUrl) {
            this.introduction = introduction;
            this.source = source;
            this.baikeUrl = baikeUrl;
        }
    }

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Map<String, Result> CACHE = new LinkedHashMap<>();

    static void fetch(String city, LocalData.Item item, int category, Callback callback) {
        String key = category + ":" + city + ":" + item.title;
        synchronized (CACHE) {
            Result cached = CACHE.get(key);
            if (cached != null) { callback.onResult(cached); return; }
        }
        EXECUTOR.execute(() -> {
            BaikeService.Entry entry = BaikeService.card(item.title);
            if (entry.abstractText.isEmpty() && !item.title.contains(city)) {
                BaikeService.Entry scoped = BaikeService.card(city + item.title);
                if (!scoped.abstractText.isEmpty()) entry = scoped;
            }
            Result result = new Result(entry.abstractText,
                entry.abstractText.isEmpty() ? "本地完整介绍" : "百度百科 · 公开词条",
                entry.pageUrl);
            synchronized (CACHE) {
                if (CACHE.size() >= 40) CACHE.remove(CACHE.keySet().iterator().next());
                CACHE.put(key, result);
            }
            callback.onResult(result);
        });
    }

    private KnowledgeService() { }
}
