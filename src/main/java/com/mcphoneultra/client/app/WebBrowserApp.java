package com.mcphoneultra.client.app;

import com.mcphoneultra.client.ui.Scroller;
import com.mcphoneultra.client.ui.Ui;
import com.mcphoneultra.client.util.Http;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 網頁瀏覽器：輸入網址，拉取網頁，顯示純文字與可點擊的連結。
 * 桌上型環境也可「系統開啟」用真正的瀏覽器看。
 */
public final class WebBrowserApp extends BaseApp {

    public WebBrowserApp() {
        super("webbrowser", true);
    }

    @Override
    protected IPhonePage createPage() {
        return new WebBrowserPage();
    }

    private static final class WebBrowserPage extends ClickablePage {
        private static final Pattern HREF = Pattern.compile(
                "<a[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        private static final Pattern TAG = Pattern.compile("<[^>]+>");
        private static final Pattern SCRIPT = Pattern.compile(
                "<(script|style)[^>]*>.*?</(script|style)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

        private String url = "https://example.com";
        private boolean typingUrl;
        private String urlInput = "";
        private final List<String> history = new ArrayList<>();
        private int historyIdx = -1;
        private String pageText = "（尚未載入）";
        private final List<String> links = new ArrayList<>();
        private final List<String> linkTargets = new ArrayList<>();
        private final Scroller scroller = new Scroller();
        private boolean loading;
        private String toast = "";
        private long toastUntil;

        private void toast(String s) {
            toast = s;
            toastUntil = System.currentTimeMillis() + 2000;
        }

        private void go(String target) {
            if (loading) return;
            String u = normalize(target);
            if (u == null) {
                toast("網址格式不對");
                return;
            }
            url = u;
            // 移除歷史裡目前的後續分支
            while (history.size() > historyIdx + 1) history.remove(history.size() - 1);
            history.add(u);
            historyIdx = history.size() - 1;
            loading = true;
            pageText = "載入中…";
            Thread t = new Thread(() -> {
                String html = Http.get(u).orElse("");
                List<String> lines = new ArrayList<>();
                List<String> hrefs = new ArrayList<>();
                List<String> targets = new ArrayList<>();
                if (html.isEmpty()) {
                    lines.add("（無法連上 " + u + "）");
                    lines.add("");
                    lines.add("可能是：沒網路、網址打錯、或網站擋掉了我們。");
                    lines.add("桌面端可以按「系統開啟」用真正的瀏覽器開。");
                } else {
                    String cleaned = SCRIPT.matcher(html).replaceAll(" ");
                    Matcher m = HREF.matcher(cleaned);
                    while (m.find()) {
                        String t2 = m.group(1).trim();
                        if (t2.isEmpty() || t2.startsWith("#") || t2.startsWith("javascript:")) continue;
                        String label = TAG.matcher(m.group(2)).replaceAll(" ").replaceAll("\\s+", " ").trim();
                        if (label.length() > 40) label = label.substring(0, 40);
                        targets.add(resolve(u, t2));
                        hrefs.add("[" + hrefs.size() + "] " + (label.isEmpty() ? t2 : label));
                    }
                    String text = TAG.matcher(cleaned).replaceAll(" ");
                    text = text.replaceAll("&nbsp;", " ").replaceAll("&amp;", "&")
                            .replaceAll("&lt;", "<").replaceAll("&gt;", ">")
                            .replaceAll("&quot;", "\"").replaceAll("&#39;", "'");
                    for (String line : text.split("\n")) {
                        String l = line.trim();
                        if (!l.isEmpty()) lines.add(l);
                    }
                }
                List<String> out = new ArrayList<>();
                out.add("==== " + u + " ====");
                out.addAll(lines);
                if (!hrefs.isEmpty()) {
                    out.add("");
                    out.add("—— 頁面連結 ——");
                    out.addAll(hrefs);
                }
                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    pageText = String.join("\n", out);
                    links.clear();
                    links.addAll(hrefs);
                    linkTargets.clear();
                    linkTargets.addAll(targets);
                    loading = false;
                    scroller.reset();
                });
            }, "mcphone-ultra-web");
            t.setDaemon(true);
            t.start();
        }

        private static String normalize(String s) {
            s = s.trim();
            if (s.isEmpty()) return null;
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://" + s;
            try {
                java.net.URI.create(s);
                return s;
            } catch (Exception e) {
                return null;
            }
        }

        private static String resolve(String base, String href) {
            try {
                return java.net.URI.create(base).resolve(href).toString();
            } catch (Exception e) {
                return href;
            }
        }

        @Override
        public void onOpen() {
            if (history.isEmpty()) go("https://example.com");
        }

        @Override
        public void render(PhoneCanvas c) {
            this.c = c;
            int x = c.x(), y = c.y(), w = c.width(), h = c.height();
            var s = c.style();
            GuiGraphics g = c.graphics();
            Ui.fill(g, x, y, w, h, s.screenBackground());

            Ui.textClipped(c, "🌐 瀏覽器", x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            int by = y + 13, bh = 12;
            if (clickOn(x + 1, by, 26, bh) && historyIdx > 0) {
                historyIdx--;
                url = history.get(historyIdx);
                loadUrl(url);
            }
            Ui.button(c, x + 1, by, 26, bh, historyIdx > 0, c.hovered(x + 1, by, 26, bh));
            Ui.buttonLabel(c, x + 1, by, 26, bh, "◀", historyIdx > 0);
            if (clickOn(x + 29, by, 26, bh) && historyIdx < history.size() - 1) {
                historyIdx++;
                url = history.get(historyIdx);
                loadUrl(url);
            }
            Ui.button(c, x + 29, by, 26, bh, historyIdx < history.size() - 1, c.hovered(x + 29, by, 26, bh));
            Ui.buttonLabel(c, x + 29, by, 26, bh, "▶", historyIdx < history.size() - 1);
            if (clickOn(x + 57, by, 30, bh)) go(url);
            Ui.button(c, x + 57, by, 30, bh, !loading, c.hovered(x + 57, by, 30, bh));
            Ui.buttonLabel(c, x + 57, by, 30, bh, loading ? "…" : "⟳", !loading);
            if (clickOn(x + 89, by, 44, bh)) typingUrl = true;
            Ui.button(c, x + 89, by, 44, bh, true, c.hovered(x + 89, by, 44, bh));
            Ui.buttonLabel(c, x + 89, by, 44, bh, "網址", true);
            if (clickOn(x + 135, by, 24, bh)) {
                if (com.november.mcphone.feature.browser.client.BrowserBackends.isMcefLoaded()) {
                    toast("主屏「瀏覽器」App 就是真實 Chromium（MCEF）");
                } else {
                    toast("真實瀏覽器需安裝 MCEF 模組（首開下載約 200MB）");
                }
            }
            Ui.button(c, x + 135, by, 24, bh, true, c.hovered(x + 135, by, 24, bh));
            Ui.buttonLabel(c, x + 135, by, 24, bh, "⚡", true);
            if (clickOn(x + 161, by, w - 162, bh)) {
                Http.openExternal(url);
                toast("已用系統瀏覽器開啟");
            }
            Ui.button(c, x + 161, by, w - 162, bh, true, c.hovered(x + 161, by, w - 162, bh));
            Ui.buttonLabel(c, x + 161, by, w - 162, bh, "系統開啟", true);

            // 網址列
            Ui.fill(g, x + 2, y + 27, w - 4, 11, 0xFF000000);
            Ui.textClipped(c, url, x + 4, y + 28, s.accentColor(), x, y + 27, w - 4, 12);

            int ty = y + 41;
            int th = h - 41 - 14;
            int rowH = c.font().lineHeight + 2;
            String[] lines = pageText.split("\n", -1);
            scroller.clamp(lines.length * rowH, th);
            int off = (int) scroller.offset();
            int startRow = off / rowH;
            int visible = th / rowH + 2;
            for (int i = startRow; i < Math.min(lines.length, startRow + visible); i++) {
                String line = lines[i];
                int ry = ty + i * rowH - off;
                int color = s.bodyColor();
                if (line.startsWith("[")) color = s.accentColor();
                if (line.startsWith("==== ")) color = s.titleColor();
                Ui.textClipped(c, line, x + 3, ry, color, x, ty, w - 4, th);
                if (line.startsWith("[") && clickOn(x + 3, ry, w - 6, rowH)) {
                    int idx = parseLinkIdx(line);
                    if (idx >= 0 && idx < linkTargets.size()) {
                        go(linkTargets.get(idx));
                        return;
                    }
                }
            }
            Ui.scrollbar(c, x + w - 3, ty, th, lines.length * rowH, off);

            if (typingUrl) {
                int px = x + 6, py = y + 50;
                Ui.fill(g, px, py, w - 12, 56, 0xFF202830);
                Ui.border(c, px, py, w - 12, 56, s.accentColor());
                Ui.text(c, "輸入網址（Enter 前往）", px + 3, py + 3, s.titleColor());
                Ui.fill(g, px + 3, py + 16, w - 18, 12, 0xFF000000);
                Ui.textClipped(c, (urlInput.isEmpty() ? url : urlInput)
                                + (System.currentTimeMillis() / 500 % 2 == 0 ? "▏" : ""),
                        px + 5, py + 17, s.bodyColor(), px, py, w - 12, 56);
                if (clickOn(px + 3, py + 32, 50, 12)) {
                    typingUrl = false;
                    go(urlInput.trim().isEmpty() ? url : urlInput);
                    urlInput = "";
                }
                Ui.button(c, px + 3, py + 32, 50, 12, true, c.hovered(px + 3, py + 32, 50, 12));
                Ui.buttonLabel(c, px + 3, py + 32, 50, 12, "前往", true);
                if (clickOn(px + 57, py + 32, 50, 12)) typingUrl = false;
                Ui.button(c, px + 57, py + 32, 50, 12, true, c.hovered(px + 57, py + 32, 50, 12));
                Ui.buttonLabel(c, px + 57, py + 32, 50, 12, "取消", true);
            }

            if (System.currentTimeMillis() < toastUntil && !toast.isEmpty()) {
                Ui.drawCentered(c, toast, x, y + h - 14, w, 12, s.titleColor());
            }
        }

        private void loadUrl(String u) {
            // 不進歷史的直接載入（上一頁/下一頁用）
            loading = true;
            pageText = "載入中…";
            Thread t = new Thread(() -> {
                String html = Http.get(u).orElse("");
                List<String> lines = new ArrayList<>();
                List<String> hrefs = new ArrayList<>();
                List<String> targets = new ArrayList<>();
                if (!html.isEmpty()) {
                    String cleaned = SCRIPT.matcher(html).replaceAll(" ");
                    Matcher m = HREF.matcher(cleaned);
                    while (m.find()) {
                        String t2 = m.group(1).trim();
                        if (t2.isEmpty() || t2.startsWith("#") || t2.startsWith("javascript:")) continue;
                        String label = TAG.matcher(m.group(2)).replaceAll(" ").replaceAll("\\s+", " ").trim();
                        if (label.length() > 40) label = label.substring(0, 40);
                        targets.add(resolve(u, t2));
                        hrefs.add("[" + hrefs.size() + "] " + (label.isEmpty() ? t2 : label));
                    }
                    String text = TAG.matcher(cleaned).replaceAll(" ");
                    text = text.replaceAll("&nbsp;", " ").replaceAll("&amp;", "&")
                            .replaceAll("&lt;", "<").replaceAll("&gt;", ">")
                            .replaceAll("&quot;", "\"").replaceAll("&#39;", "'");
                    for (String line : text.split("\n")) {
                        String l = line.trim();
                        if (!l.isEmpty()) lines.add(l);
                    }
                }
                List<String> out = new ArrayList<>();
                out.add("==== " + u + " ====");
                out.addAll(lines);
                if (!hrefs.isEmpty()) {
                    out.add("");
                    out.add("—— 頁面連結 ——");
                    out.addAll(hrefs);
                }
                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    pageText = String.join("\n", out);
                    links.clear();
                    links.addAll(hrefs);
                    linkTargets.clear();
                    linkTargets.addAll(targets);
                    loading = false;
                    scroller.reset();
                });
            }, "mcphone-ultra-web2");
            t.setDaemon(true);
            t.start();
        }

        private static int parseLinkIdx(String line) {
            try {
                int end = line.indexOf(']');
                if (end <= 1) return -1;
                return Integer.parseInt(line.substring(1, end));
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            scroller.onWheel(amount, pageText.split("\n", -1).length * (c.font().lineHeight + 2), 130);
            return true;
        }

        private PhoneCanvas c;

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (typingUrl) {
                if (key == GLFW.GLFW_KEY_ENTER) {
                    typingUrl = false;
                    go(urlInput.trim().isEmpty() ? url : urlInput);
                    urlInput = "";
                } else if (key == GLFW.GLFW_KEY_BACKSPACE && !urlInput.isEmpty()) {
                    urlInput = urlInput.substring(0, urlInput.length() - 1);
                }
                return true;
            }
            return false;
        }

        @Override
        public boolean charTyped(char ch, int mods) {
            if (typingUrl) {
                if (ch >= 32 && ch != 127) urlInput += ch;
                return true;
            }
            return false;
        }

        @Override
        public boolean capturesKeyboard() {
            return typingUrl;
        }

        @Override
        public boolean onBack() {
            if (typingUrl) {
                typingUrl = false;
                return true;
            }
            return false;
        }
    }
}
