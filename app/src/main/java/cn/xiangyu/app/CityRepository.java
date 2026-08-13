package cn.xiangyu.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class CityRepository {
    static final class City {
        final String code;
        final String name;
        final String province;
        final double lat;
        final double lon;
        final String officialName;
        final boolean curatedContent;

        City(String code, String name, String province, double lat, double lon,
             String officialName, boolean curatedContent) {
            this.code = code;
            this.name = name;
            this.province = province;
            this.lat = lat;
            this.lon = lon;
            this.officialName = officialName;
            this.curatedContent = curatedContent;
        }
    }

    private final List<City> cities;

    CityRepository(Context context) {
        cities = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            context.getAssets().open("prefecture_cities.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#") || line.trim().isEmpty()) continue;
                String[] p = line.split("\\|", -1);
                if (p.length != 7 || !("curated".equals(p[6]) || "lookup".equals(p[6]))) {
                    throw new IllegalStateException("Invalid city content status: " + line);
                }
                cities.add(new City(p[0], p[1], p[2], Double.parseDouble(p[3]),
                    Double.parseDouble(p[4]), p[5], "curated".equals(p[6])));
            }
            if (cities.size() != 337) throw new IllegalStateException(
                "Expected 337 prefecture city records, got " + cities.size());
            validateIndex();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load prefecture city index", exception);
        }
    }

    List<City> all() { return Collections.unmodifiableList(cities); }

    City findByCode(String code) {
        for (City city : cities) if (city.code.equals(code)) return city;
        return null;
    }

    List<String> provinces() {
        Set<String> result = new LinkedHashSet<>();
        for (City city : cities) result.add(city.province);
        return new ArrayList<>(result);
    }

    List<City> inProvince(String province) {
        List<City> result = new ArrayList<>();
        for (City city : cities) if (city.province.equals(province)) result.add(city);
        result.sort(Comparator.comparing(value -> value.code));
        return result;
    }

    City findByName(String... candidates) {
        for (String candidate : candidates) {
            String normalized = normalize(candidate);
            if (normalized.isEmpty()) continue;
            for (City city : cities) {
                if (normalize(city.name).equals(normalized)
                    || normalize(city.officialName).equals(normalized)
                    || normalized.contains(normalize(city.officialName))
                    || normalize(city.officialName).contains(normalized)) return city;
            }
        }
        return null;
    }

    private void validateIndex() {
        Set<String> codes = new HashSet<>();
        Set<String> officialNames = new HashSet<>();
        for (City city : cities) {
            if (!city.code.matches("\\d{4}") || city.name.trim().isEmpty()
                    || city.province.trim().isEmpty() || city.officialName.trim().isEmpty()) {
                throw new IllegalStateException("Incomplete prefecture record: " + city.code);
            }
            if (!codes.add(city.code)) throw new IllegalStateException("Duplicate city code: " + city.code);
            if (!officialNames.add(city.province + "|" + city.officialName)) {
                throw new IllegalStateException("Duplicate prefecture name: " + city.officialName);
            }
        }
    }

    City nearest(double lat, double lon) {
        City best = cities.get(0);
        double bestDistance = Double.MAX_VALUE;
        for (City city : cities) {
            double latDistance = lat - city.lat;
            double lonDistance = (lon - city.lon) * Math.cos(Math.toRadians(lat));
            double distance = latDistance * latDistance + lonDistance * lonDistance;
            if (distance < bestDistance) {
                best = city;
                bestDistance = distance;
            }
        }
        return best;
    }

    City nearestInProvince(String province, double lat, double lon) {
        City best = null;
        double bestDistance = Double.MAX_VALUE;
        for (City city : cities) {
            if (!city.province.equals(province)) continue;
            double latDistance = lat - city.lat;
            double lonDistance = (lon - city.lon) * Math.cos(Math.toRadians(lat));
            double distance = latDistance * latDistance + lonDistance * lonDistance;
            if (distance < bestDistance) {
                best = city;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.trim()
            .replace("特别行政区", "")
            .replace("自治州", "")
            .replace("地区", "")
            .replace("市", "")
            .replace("盟", "");
    }
}
