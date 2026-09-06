package com.mcphoneultra.client.app;

import com.mcphoneultra.client.ui.Scroller;
import com.mcphoneultra.client.ui.Ui;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 影片搜索列表页（Bilibili / YouTube 共用）：
 * 输入关键词 → Fetcher 拉列表 → 点一行用 Opener 在系统浏览器打开。
 * 任何网络失败都保留「系統瀏覽器搜尋」这条退路。
 */
public final class VideoListPage extends ClickablePage {

    public record Hit(String id, String title, String author, String extra) {
    }

    public interface Fetcher {
        /** 后台线程调用。抛异常表示失败。 */
        List<Hit> fetch(String keyword, boolean hot) throws Exception;
    }

    public interface Opener {
        void open(Hit hit);
    }

    public interface SystemSearcher {
        String searchUrl(String keyword);
    }

    private final Fetcher fetcher;
    private final Opener opener;
    private final SystemSearcher systemSearcher;
    private final String title;

    private String keyword = "";
    private List<Hit> hits = List.of();
    private final Scroller scroller = new Scroller();
    private boolean loading;
    private String message = "輸入關鍵字搜尋，或點「熱門推薦」";
    private long messageUntil;

    public VideoListPage(String title, Fetcher fetcher, Opener opener, SystemSearcher systemSearcher) {
        this.title = title;
        this.fetcher = fetcher;
        this.opener = opener;
        this.systemSearcher = systemSearcher;
    }

    private void msg(String s) {
        message = s;
        messageUntil = System.currentTimeMillis() + 4000;
    }

    private void search(boolean hot) {
        if (loading) return;
        loading = true;
        hits = List.of();
        scroller.reset();
        msg("載入中…");
        Thread t = new Thread(() -> {
            try {
                List<Hit> list = fetcher.fetch(keyword, hot);
                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    hits = list;
                    loading = false;
                    scroller.reset();
                    if (list.isEmpty()) msg("沒有結果");
                    else message = list.size() + " 筆結果";
                });
            } catch (Exception e) {
                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    loading = false;
                    msg("載入失敗：" + (e.getMessage() == null ? "網路或介面變更" : e.getMessage()));
                });
            }
        }, "mcphone-ultra-fetch");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void render(PhoneCanvas c) {
        int x = c.x();
        int y = c.y();
        int w = c.width();
        int h = c.height();
        var s = c.style();
        GuiGraphics g = c.graphics();
        Ui.fill(g, x, y, w, h, s.screenBackground());

        Ui.text(c, title, x + 3, y + 2, s.titleColor());
        Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

        // 搜索框
        int iy = y + 13;
        Ui.fill(g, x + 1, iy, w - 2, 13, 0xFF000000);
        Ui.border(c, x + 1, iy, w - 2, 13, s.buttonDisabledColor());
        String shown = keyword + (System.currentTimeMillis() / 500 % 2 == 0 ? "▏" : "");
        Ui.textClipped(c, shown, x + 4, iy + 2, s.bodyColor(), x, iy, w - 6, 13);
        if (clickOn(x + 1, iy, w - 2, 13)) {
            // 点搜索框 = 聚焦（键盘已全局捕获）
        }

        int by = iy + 15;
        int bh = 12;
        int bw = (w - 8) / 3;
        if (clickOn(x + 1, by, bw, bh)) {
            search(false);
        }
        Ui.button(c, x + 1, by, bw, bh, !loading, c.hovered(x + 1, by, bw, bh));
        Ui.buttonLabel(c, x + 1, by, bw, bh, "搜尋", !loading);
        if (clickOn(x + 2 + bw, by, bw, bh)) {
            search(true);
        }
        Ui.button(c, x + 2 + bw, by, bw, bh, !loading, c.hovered(x + 2 + bw, by, bw, bh));
        Ui.buttonLabel(c, x + 2 + bw, by, bw, bh, "熱門", !loading);
        if (clickOn(x + 3 + bw * 2, by, bw, bh)) {
            com.mcphoneultra.client.util.Http.openExternal(systemSearcher.searchUrl(keyword));
            msg("已在系統瀏覽器搜尋");
        }
        Ui.button(c, x + 3 + bw * 2, by, bw, bh, true, c.hovered(x + 3 + bw * 2, by, bw, bh));
        Ui.buttonLabel(c, x + 3 + bw * 2, by, bw, bh, "瀏覽器搜", true);

        int listY = by + bh + 2;
        int listH = h - (listY - y) - 12;
        if (loading) {
            Ui.drawCentered(c, "載入中…", x, listY, w, listH, s.subtleColor());
        } else {
            int rowH = 30;
            int contentH = hits.size() * rowH;
            scroller.clamp(contentH, listH);
            int off = (int) scroller.offset();
            for (int i = 0; i < hits.size(); i++) {
                int ry = listY + i * rowH - off;
                if (ry + rowH < listY || ry > listY + listH) continue;
                Hit hit = hits.get(i);
                if (clickOn(x, ry, w - 2, rowH)) {
                    opener.open(hit);
                    msg("已在系統瀏覽器開啟");
                    break;
                }
                if (c.hovered(x, ry, w - 2, rowH)) Ui.fill(g, x, ry, w - 2, rowH, s.pressedOverlay());
                String t1 = hit.title().replaceAll("<[^>]+>", "");
                if (t1.length() > 17) t1 = t1.substring(0, 16) + "…";
                Ui.textClipped(c, "▶ " + t1, x + 3, ry + 1, s.titleColor(), x, ry, w, rowH);
                String meta = hit.author() + " · " + hit.extra();
                if (meta.length() > 18) meta = meta.substring(0, 17) + "…";
                Ui.textClipped(c, meta, x + 3, ry + 11, s.subtleColor(), x, ry, w, rowH);
                Ui.hline(g, x + 2, x + w - 2, ry + rowH - 1, s.buttonDisabledColor());
            }
            if (hits.isEmpty()) {
                Ui.drawCentered(c, "沒有結果", x, listY, w, listH, s.subtleColor());
            }
            Ui.scrollbar(c, x + w - 3, listY, listH, contentH, off);
        }

        if (System.currentTimeMillis() < messageUntil) {
            Ui.textClipped(c, message, x + 3, y + h - 11, s.accentColor(), x, y, w, 12);
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        scroller.onWheel(amount, hits.size() * 30, 110);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            search(false);
            return true;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE && !keyword.isEmpty()) {
            keyword = keyword.substring(0, keyword.length() - 1);
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(char ch, int mods) {
        if (ch >= 32 && ch != 127) keyword += ch;
        return true;
    }

    @Override
    public boolean capturesKeyboard() {
        return true;
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
