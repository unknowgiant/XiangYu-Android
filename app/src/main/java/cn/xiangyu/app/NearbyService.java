package cn.xiangyu.app;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads nearby dining, budget lodging and transport POI from official Amap Web Service. */
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
        if (!AmapPoiService.configured()) {
            callback.onResult(new Result(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), false));
            return;
        }
        EXECUTOR.execute(() -> {
            List<String> food = labels(AmapPoiService.around(
                lat, lon, AmapPoiService.DINING_TYPES, 5000, 4));
            List<String> hotels = new ArrayList<>();
            for (AmapPoiService.Poi poi : AmapPoiService.around(lat, lon,
                    "快捷酒店|宾馆|青年旅舍|客栈|民宿",
                    AmapPoiService.LODGING_TYPES, 5000, 12)) {
                if (isUpscale(poi)) continue;
                hotels.add(poi.detail(true));
                if (hotels.size() >= 4) break;
            }
            List<String> transport = labels(AmapPoiService.around(
                lat, lon, AmapPoiService.TRANSPORT_TYPES, 8000, 5));
            boolean fresh = !food.isEmpty() || !hotels.isEmpty() || !transport.isEmpty();
            callback.onResult(new Result(food, hotels, transport, fresh));
        });
    }

    private static List<String> labels(List<AmapPoiService.Poi> pois) {
        List<String> result = new ArrayList<>();
        for (AmapPoiService.Poi poi : pois) result.add(poi.detail(true));
        return result;
    }

    private static boolean isUpscale(AmapPoiService.Poi poi) {
        if (AmapPoiService.isLikelyUpscale(poi)) return true;
        String value = poi.name + poi.type;
        return value.contains("五星") || value.contains("四星") || value.contains("三星")
            || value.contains("豪华型") || value.contains("高档型") || value.contains("高档宾馆")
            || value.contains("丽思卡尔顿") || value.contains("华尔道夫") || value.contains("四季酒店")
            || value.contains("瑞吉") || value.contains("柏悦") || value.contains("君悦")
            || value.contains("洲际") || value.contains("香格里拉") || value.contains("文华东方");
    }

    private NearbyService() { }
}
