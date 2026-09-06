package com.mcphoneultra.client.app;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcphoneultra.client.util.Http;
import com.november.mcphone.api.client.ui.IPhonePage;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** YouTube：从搜索结果页解析 ytInitialData（无需 API key），点开在系统浏览器播放。 */
public final class YoutubeApp extends BaseApp {

    public YoutubeApp() {
        super("youtube", true);
    }

    @Override
    protected IPhonePage createPage() {
        return new VideoListPage(
                "▶ YouTube",
                YoutubeApp::fetch,
                hit -> Http.openExternal("https://www.youtube.com/watch?v=" + hit.id()),
                kw -> "https://www.youtube.com/results?search_query=" + VideoListPage.enc(kw));
    }

    private static final Pattern INITIAL = Pattern.compile("var ytInitialData = (\\{.*?\\});</script>");

    private static List<VideoListPage.Hit> fetch(String keyword, boolean hot) throws Exception {
        String url = hot
                ? "https://www.youtube.com/feed/trending"
                : "https://www.youtube.com/results?search_query=" + VideoListPage.enc(keyword);
        String html = Http.get(url).orElseThrow(() -> new Exception("請求失敗"));
        Matcher m = INITIAL.matcher(html);
        if (!m.find()) throw new Exception("解析失敗，改用瀏覽器搜尋");
        JsonObject data = JsonParser.parseString(m.group(1)).getAsJsonObject();
        JsonObject contents = data.getAsJsonObject("contents");
        JsonElement primary = find(contents, "twoColumnSearchResultsRenderer");
        if (primary == null) primary = find(contents, "twoColumnBrowseResultsRenderer");
        if (primary == null) throw new Exception("沒有內容，改用瀏覽器搜尋");

        List<VideoListPage.Hit> out = new ArrayList<>();
        collect(primary, out, hot);
        if (out.isEmpty()) throw new Exception("沒有結果，改用瀏覽器搜尋");
        return out;
    }

    private static void collect(JsonElement node, List<VideoListPage.Hit> out, boolean hot) {
        if (node == null || out.size() >= 15) return;
        if (node.isJsonObject()) {
            JsonObject o = node.getAsJsonObject();
            JsonElement vr = o.get("videoRenderer");
            if (vr != null && vr.isJsonObject()) {
                JsonObject v = vr.getAsJsonObject();
                String id = v.has("videoId") ? v.get("videoId").getAsString() : "";
                String title = text(v.get("title"));
                String author = text(v.get("ownerText"));
                String views = text(v.get("viewCountText"));
                String len = text(v.get("lengthText"));
                if (!id.isEmpty() && !title.isEmpty()) {
                    out.add(new VideoListPage.Hit(id, title, author, views + " · " + len));
                }
            }
            for (String key : o.keySet()) {
                JsonElement child = o.get(key);
                if (child.isJsonArray() || child.isJsonObject()) collect(child, out, hot);
            }
        } else if (node.isJsonArray()) {
            for (JsonElement e : node.getAsJsonArray()) collect(e, out, hot);
        }
    }

    private static JsonElement find(JsonElement node, String key) {
        if (node == null) return null;
        if (node.isJsonObject()) {
            JsonObject o = node.getAsJsonObject();
            if (o.has(key)) return o.get(key);
            for (String k : o.keySet()) {
                JsonElement r = find(o.get(k), key);
                if (r != null) return r;
            }
        } else if (node.isJsonArray()) {
            for (JsonElement e : node.getAsJsonArray()) {
                JsonElement r = find(e, key);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static String text(JsonElement e) {
        if (e == null || !e.isJsonObject()) return "";
        JsonObject o = e.getAsJsonObject();
        if (o.has("simpleText")) return o.get("simpleText").getAsString();
        JsonElement runs = o.get("runs");
        if (runs != null && runs.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement r : runs.getAsJsonArray()) {
                if (r.isJsonObject() && r.getAsJsonObject().has("text")) {
                    sb.append(r.getAsJsonObject().get("text").getAsString());
                }
            }
            return sb.toString();
        }
        return "";
    }
}
