package com.mcphoneultra.client.app;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcphoneultra.client.util.Http;
import com.november.mcphone.api.client.ui.IPhonePage;

import java.util.ArrayList;
import java.util.List;

/** Bilibili：搜索与热门走 bilibili 公开接口，点开在系统浏览器播放。 */
public final class BilibiliApp extends BaseApp {

    public BilibiliApp() {
        super("bilibili", true);
    }

    @Override
    protected IPhonePage createPage() {
        return new VideoListPage(
                "📺 Bilibili",
                BilibiliApp::fetch,
                hit -> Http.openExternal("https://www.bilibili.com/video/" + hit.id()),
                kw -> "https://search.bilibili.com/all?keyword=" + VideoListPage.enc(kw));
    }

    private static List<VideoListPage.Hit> fetch(String keyword, boolean hot) throws Exception {
        String url;
        if (hot) {
            url = "https://api.bilibili.com/x/web-interface/popular?ps=20&pn=1";
        } else {
            url = "https://api.bilibili.com/x/web-interface/search/type?search_type=video&page=1&keyword="
                    + VideoListPage.enc(keyword);
        }
        String body = Http.get(url).orElseThrow(() -> new Exception("請求失敗"));
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        int code = root.has("code") ? root.get("code").getAsInt() : -1;
        if (code != 0) {
            throw new Exception("介面拒絕（code=" + code + "），改用瀏覽器搜尋");
        }
        List<VideoListPage.Hit> out = new ArrayList<>();
        if (hot) {
            JsonArray list = root.getAsJsonObject("data").getAsJsonArray("list");
            for (JsonElement e : list) {
                JsonObject o = e.getAsJsonObject();
                String bvid = o.has("bvid") ? o.get("bvid").getAsString() : "";
                String title = o.has("title") ? o.get("title").getAsString() : "（無標題）";
                String author = o.has("owner") ? o.getAsJsonObject("owner").get("name").getAsString() : "";
                String view = o.has("stat") ? fmtView(o.getAsJsonObject("stat").get("view").getAsLong()) : "";
                String dur = o.has("duration") ? fmtDur(o.get("duration").getAsInt()) : "";
                out.add(new VideoListPage.Hit(bvid, title, author, view + " · " + dur));
            }
        } else {
            JsonElement result = root.getAsJsonObject("data").get("result");
            if (result == null || !result.isJsonArray()) throw new Exception("沒有結果");
            for (JsonElement e : result.getAsJsonArray()) {
                JsonObject o = e.getAsJsonObject();
                String bvid = o.has("bvid") ? o.get("bvid").getAsString() : "";
                String title = o.has("title") ? o.get("title").getAsString() : "（無標題）";
                String author = o.has("author") ? o.get("author").getAsString() : "";
                String play = o.has("play") ? fmtPlay(o.get("play").getAsString()) : "";
                String dur = o.has("duration") ? o.get("duration").getAsString() : "";
                out.add(new VideoListPage.Hit(bvid, title, author, play + " · " + dur));
            }
        }
        return out;
    }

    private static String fmtView(long v) {
        if (v >= 10000) return String.format("%.1f萬", v / 10000.0);
        return v + "";
    }

    private static String fmtPlay(String p) {
        if (p.contains("万")) return p;
        try {
            long v = Long.parseLong(p);
            if (v >= 10000) return String.format("%.1f萬", v / 10000.0);
            return v + "";
        } catch (NumberFormatException e) {
            return p;
        }
    }

    private static String fmtDur(int sec) {
        return (sec / 60) + ":" + String.format("%02d", sec % 60);
    }
}
