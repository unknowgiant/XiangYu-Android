package cn.xiangyu.app;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class WeatherService {
    interface Callback { void onResult(Weather weather); }

    static final class Weather {
        final int temperature;
        final int high;
        final int low;
        final int code;
        final int humidity;
        final double wind;
        final boolean fresh;

        Weather(int temperature, int high, int low, int code, int humidity, double wind, boolean fresh) {
            this.temperature = temperature;
            this.high = high;
            this.low = low;
            this.code = code;
            this.humidity = humidity;
            this.wind = wind;
            this.fresh = fresh;
        }

        String description() {
            if (code == 0) return "晴";
            if (code <= 3) return "多云";
            if (code == 45 || code == 48) return "有雾";
            if (code <= 57) return "细雨";
            if (code <= 67) return "有雨";
            if (code <= 77) return "有雪";
            if (code <= 82) return "阵雨";
            if (code <= 86) return "阵雪";
            return "雷雨";
        }
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    static void fetch(double lat, double lon, Callback callback) {
        EXECUTOR.execute(() -> {
            Weather result = new Weather(24, 28, 20, 1, 62, 2.4, false);
            try {
                String endpoint = "https://api.open-meteo.com/v1/forecast?latitude=" + lat
                    + "&longitude=" + lon
                    + "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m"
                    + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1";
                HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(7000);
                connection.setRequestProperty("User-Agent", "XiangYu-Android/1.0");
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder body = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                    JSONObject root = new JSONObject(body.toString());
                    JSONObject current = root.getJSONObject("current");
                    JSONObject daily = root.getJSONObject("daily");
                    result = new Weather(
                        (int) Math.round(current.getDouble("temperature_2m")),
                        (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)),
                        (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)),
                        current.getInt("weather_code"),
                        current.getInt("relative_humidity_2m"),
                        current.getDouble("wind_speed_10m"), true);
                }
            } catch (Exception ignored) { }
            callback.onResult(result);
        });
    }

    private WeatherService() {}
}
