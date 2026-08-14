package cn.xiangyu.app;

import java.util.ArrayList;
import java.util.List;

/** Nearby cards use the current city lists; live routing is opened in the map app. */
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

    static void fetch(double lat, double lon, Callback callback) {
        callback.onResult(new Result(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), false));
    }

    private NearbyService() { }
}
