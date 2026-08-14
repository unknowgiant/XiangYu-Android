package cn.xiangyu.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Public Baidu Baike endpoints used by the app. */
final class BaikeService {
    private static long lastRequestAt;
    static final class Entry {
        final String title;
        final String description;
        final String abstractText;
        final String imageUrl;
        final String pageUrl;
        final List<String> famousSights;

        Entry(String title, String description, String abstractText, String imageUrl,
              String pageUrl, List<String> famousSights) {
            this.title = title;
            this.description = description;
            this.abstractText = abstractText;
            this.imageUrl = imageUrl;
            this.pageUrl = pageUrl;
            this.famousSights = famousSights;
        }
    }

    static Entry card(String keyword) {
        try {
            String endpoint = "https://baike.baidu.com/api/openapi/BaikeLemmaCardApi"
                + "?scope=103&format=json&appid=379020&bk_length=900&bk_key=" + encode(keyword);
            JSONObject root = new JSONObject(request(endpoint));
            if (root.optString("title", "").isEmpty()) {
                Thread.sleep(650);
                root = new JSONObject(request(endpoint));
            }
            String title = root.optString("title", "").trim();
            String description = clean(root.optString("desc", ""));
            String abstractText = clean(root.optString("abstract", ""));
            String image = root.optString("image", "").replace("http://", "https://");
            String page = root.optString("wapUrl", root.optString("url", ""))
                .replace("http://", "https://");
            if (page.isEmpty()) page = pageUrl(keyword);
            List<String> famousSights = new ArrayList<>();
            JSONArray cards = root.optJSONArray("card");
            if (cards != null) {
                for (int i = 0; i < cards.length(); i++) {
                    JSONObject value = cards.optJSONObject(i);
                    if (value == null || !value.optString("name").contains("著名景点")) continue;
                    JSONArray entries = value.optJSONArray("value");
                    if (entries == null) continue;
                    for (int j = 0; j < entries.length(); j++) {
                        for (String sight : clean(entries.optString(j)).split("[、，,；;]")) {
                            String normalized = sight.replaceAll("[0-9]+$", "").trim();
                            if (normalized.length() >= 2 && !famousSights.contains(normalized)) {
                                famousSights.add(normalized);
                            }
                        }
                    }
                }
            }
            return new Entry(title, description, abstractText, image, page, famousSights);
        } catch (Exception ignored) {
            return empty(keyword);
        }
    }

    static List<Entry> suggest(String query) {
        List<Entry> result = new ArrayList<>();
        try {
            String endpoint = "https://baike.baidu.com/api/searchui/suggest?enc=utf8&wd=" + encode(query);
            JSONArray list = new JSONObject(request(endpoint)).optJSONArray("list");
            if (list == null || list.length() == 0) {
                Thread.sleep(650);
                list = new JSONObject(request(endpoint)).optJSONArray("list");
            }
            if (list == null) return result;
            Set<String> used = new LinkedHashSet<>();
            for (int i = 0; i < list.length(); i++) {
                JSONObject value = list.optJSONObject(i);
                if (value == null) continue;
                String title = clean(value.optString("lemmaTitle", ""));
                String description = clean(value.optString("lemmaDesc", ""));
                String image = value.optString("abstractPic", "").replace("http://", "https://");
                if (title.length() < 2 || title.length() > 30 || !used.add(title)) continue;
                result.add(new Entry(title, description, "", image, pageUrl(title), new ArrayList<>()));
            }
        } catch (Exception ignored) { }
        return result;
    }

    static String pageUrl(String keyword) {
        return "https://baike.baidu.com/item/" + encode(keyword);
    }

    private static Entry empty(String keyword) {
        return new Entry("", "", "", "", pageUrl(keyword), new ArrayList<>());
    }

    private static synchronized String request(String endpoint) throws Exception {
        long wait = 220L - (System.currentTimeMillis() - lastRequestAt);
        if (wait > 0) Thread.sleep(wait);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(7500);
        connection.setRequestProperty("Accept", "application/json,text/plain,*/*");
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        connection.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/132 Mobile Safari/537.36 XiangYu/1.8.7");
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && body.length() < 1_500_000) body.append(line);
        } finally {
            lastRequestAt = System.currentTimeMillis();
            connection.disconnect();
        }
        return body.toString();
    }

    private static String clean(String value) {
        return value.replaceAll("<script.*?</script>", " ").replaceAll("<style.*?</style>", " ")
            .replaceAll("<[^>]+>", " ").replace("&quot;", "\"")
            .replace("&amp;", "&").replace("&#39;", "'").replace("&nbsp;", " ")
            .replaceAll("&#x?[0-9a-fA-F]+;", " ").replaceAll("\\s+", " ").trim();
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, StandardCharsets.UTF_8.name()); }
        catch (Exception ignored) { return value; }
    }

    private BaikeService() { }
}
