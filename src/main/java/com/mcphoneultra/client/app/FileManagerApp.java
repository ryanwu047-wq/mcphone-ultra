package com.mcphoneultra.client.app;

import com.mcphoneultra.client.io.Paths;
import com.mcphoneultra.client.io.Store;
import com.mcphoneultra.client.ui.Scroller;
import com.mcphoneultra.client.ui.TextBuffer;
import com.mcphoneultra.client.ui.Ui;
import com.mcphoneultra.client.util.ArchiveUtil;
import com.mcphoneultra.client.util.Http;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import com.november.mcphone.api.client.ui.PhoneStyle;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 頁面點擊模式說明（本包所有頁面通用）：
 * MCphone 只回傳 mouseClicked（按下），沒有放開事件。所以每個頁面在 mouseClicked 裡
 * 記下「最近一次點擊」，render 每幀檢查「有沒有未消費的點擊」並在布局座標上做命中判定，
 * 命中即消費。這樣一次點擊只會觸發一個按鈕，也不會漏掉點擊。
 */
abstract class ClickablePage implements IPhonePage, com.mcphoneultra.client.ui.DragPage {

    private boolean clickConsumed = true;
    private int clickX;
    private int clickY;
    private int clickButton;

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        clickConsumed = false;
        clickX = (int) mouseX;
        clickY = (int) mouseY;
        clickButton = button;
        return true;
    }

    /** 左键按下且尚未被消费的点击，是否落在 (x,y,w,h) 内。命中即消费。 */
    protected boolean clickOn(int x, int y, int w, int h) {
        if (clickConsumed) return false;
        if (clickButton != 0) return false;
        boolean hit = Ui.hit(x, y, w, h, clickX, clickY);
        if (hit) clickConsumed = true;
        return hit;
    }

    /** 右键点击（button==1）是否落在区域内，命中即消费。 */
    protected boolean rightClickOn(int x, int y, int w, int h) {
        if (clickConsumed) return false;
        if (clickButton != 1) return false;
        boolean hit = Ui.hit(x, y, w, h, clickX, clickY);
        if (hit) clickConsumed = true;
        return hit;
    }

    /** 任何尚未消费的点击是否落在区域内（左右键皆可）。 */
    protected boolean anyClickOn(int x, int y, int w, int h) {
        if (clickConsumed) return false;
        boolean hit = Ui.hit(x, y, w, h, clickX, clickY);
        if (hit) clickConsumed = true;
        return hit;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        return false;
    }
}

/**
 * 檔案管理：浏览/打开/新建/删除 config/mcphone/ultra/files/ 下的文件与文件夹。
 * 內建：網頁下載（二進位）、zip/7z/rar 解壓（含密碼輸入）、zip 壓縮、
 * 文字編輯器（txt/py/js/…）與 Hex 檢視/編輯、系統開啟。
 */
public final class FileManagerApp extends BaseApp {

    public FileManagerApp() {
        super("filemanager", true);
    }

    @Override
    protected IPhonePage createPage() {
        return new FileManagerPage();
    }

    private static final class FileManagerPage extends ClickablePage {

        private static final List<String> TEXT_EXT = List.of(
                "txt", "json", "log", "md", "py", "js", "csv", "lang", "toml",
                "cfg", "properties", "xml", "yml", "ini", "html", "css", "java");

        private Path cwd = Paths.dir("files");
        private final List<Path> entries = new ArrayList<>();
        private final Scroller scroller = new Scroller();
        private String toast = "";
        private long toastUntil;

        // 編輯器狀態
        private Path editing;
        private boolean hexMode;
        private final TextBuffer buffer = new TextBuffer();
        private byte[] hexBytes;
        private int hexScroll;
        private int hexSel = -1;
        private String hexInput = "";

        // 下載 / 解壓 / 命名
        private boolean downloadDialog;
        private String urlInput = "";
        private boolean extracting;
        private Path pendingExtract;
        private String passInput = "";
        private boolean passDialog;
        private boolean compressing;
        private String pendingDelete;

        private void toast(String s) {
            toast = s;
            toastUntil = System.currentTimeMillis() + 2200;
        }

        private void reload() {
            entries.clear();
            try (var stream = Files.list(cwd)) {
                stream.forEach(entries::add);
            } catch (IOException e) {
                toast("無法讀取目錄");
            }
            entries.sort(Comparator.comparing((Path p) -> !Files.isDirectory(p))
                    .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
            scroller.reset();
        }

        @Override
        public void onOpen() {
            reload();
        }

        // ================= 動作 =================

        private void openEntry(Path p) {
            if (Files.isDirectory(p)) {
                cwd = p;
                reload();
                return;
            }
            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (ArchiveUtil.isArchive(n)) {
                pendingExtract = p;
                passDialog = true;
                passInput = "";
                return;
            }
            if (isTextEditable(p)) {
                startEdit(p);
                return;
            }
            if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                    || n.endsWith(".gif") || n.endsWith(".bmp")) {
                Http.openExternal(p.toUri().toString());
                toast("圖片已用系統程式開啟");
                return;
            }
            Http.openExternal(p.toUri().toString());
            toast("已用系統程式開啟");
        }

        private void startEdit(Path p) {
            editing = p;
            hexMode = false;
            buffer.setText(Store.readAll(p).orElse(""));
            buffer.resetView();
            try {
                hexBytes = Files.readAllBytes(p);
            } catch (IOException e) {
                hexBytes = new byte[0];
            }
            hexScroll = 0;
            hexSel = -1;
        }

        private void saveEdit() {
            if (editing == null) return;
            try {
                if (hexMode && hexBytes != null) {
                    Files.write(editing, hexBytes);
                } else {
                    Store.writeAll(editing, buffer.text());
                }
                toast("已儲存 " + editing.getFileName());
            } catch (IOException e) {
                toast("儲存失敗");
            }
        }

        private void deletePath(Path p) {
            try {
                ArchiveUtil.deleteRecursively(p);
                toast("已刪除");
            } catch (IOException e) {
                toast("刪除失敗");
            }
            reload();
        }

        private void doExtract() {
            if (pendingExtract == null) return;
            Path src = pendingExtract;
            String pass = passInput;
            extracting = true;
            toast("解壓中…");
            Thread t = new Thread(() -> {
                String msg;
                try {
                    Path dest = src.resolveSibling(stripExt(src.getFileName().toString()));
                    ArchiveUtil.extract(src, dest, pass.isEmpty() ? null : pass);
                    msg = "解壓完成 → " + dest.getFileName();
                } catch (IOException e) {
                    msg = "解壓失敗：" + e.getMessage();
                }
                final String m = msg;
                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    extracting = false;
                    toast(m);
                    reload();
                });
            }, "mcphone-ultra-extract");
            t.setDaemon(true);
            t.start();
        }

        private void doDownload() {
            String url = urlInput.trim();
            if (url.isEmpty()) {
                toast("輸入網址");
                return;
            }
            downloadDialog = false;
            urlInput = "";
            String fname = url.substring(url.lastIndexOf('/') + 1);
            if (fname.isEmpty() || fname.contains("?")) fname = "download.bin";
            final String fileName = fname;
            Path target = cwd.resolve(Paths.safeName(fileName));
            toast("下載中…");
            Thread t = new Thread(() -> {
                byte[] data = Http.getBytes(url);
                String msg;
                if (data == null) {
                    msg = "下載失敗（網址或網路）";
                } else {
                    try {
                        Files.write(target, data);
                        msg = "已下載 " + fileName + "（" + data.length + "B）";
                    } catch (IOException e) {
                        msg = "寫檔失敗";
                    }
                }
                final String m = msg;
                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    toast(m);
                    reload();
                });
            }, "mcphone-ultra-download");
            t.setDaemon(true);
            t.start();
        }

        private static String stripExt(String name) {
            int dot = name.lastIndexOf('.');
            return dot > 0 ? name.substring(0, dot) : name + "_extract";
        }

        private boolean isTextEditable(Path p) {
            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
            int dot = n.lastIndexOf('.');
            if (dot < 0) return false;
            if (!TEXT_EXT.contains(n.substring(dot + 1))) return false;
            try {
                return Files.size(p) < 1024 * 1024;
            } catch (IOException e) {
                return false;
            }
        }

        // ================= 渲染 =================

        @Override
        public void render(PhoneCanvas c) {
            int x = c.x();
            int y = c.y();
            int w = c.width();
            int h = c.height();
            var s = c.style();
            GuiGraphics g = c.graphics();

            if (editing != null) {
                renderEditor(c, x, y, w, h, s, g);
                return;
            }

            Ui.fill(g, x, y, w, h, s.screenBackground());

            String title = cwd.equals(Paths.base()) ? "檔案管理" : "▸ " + cwd.getFileName();
            Ui.textClipped(c, title, x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            int by = y + 13;
            int bh = 12;
            boolean up = cwd.getParent() != null && Files.isDirectory(cwd.getParent());

            if (clickOn(x + 1, by, 22, bh) && up) {
                cwd = cwd.getParent();
                reload();
            }
            Ui.button(c, x + 1, by, 22, bh, up, c.hovered(x + 1, by, 22, bh));
            Ui.buttonLabel(c, x + 1, by, 22, bh, "上級", up);

            if (clickOn(x + 25, by, 22, bh)) {
                cwd = Paths.dir("files");
                reload();
            }
            Ui.button(c, x + 25, by, 22, bh, true, c.hovered(x + 25, by, 22, bh));
            Ui.buttonLabel(c, x + 25, by, 22, bh, "主頁", true);

            if (clickOn(x + 49, by, 22, bh)) {
                try {
                    Files.createDirectories(cwd.resolve(Paths.safeName("新資料夾")));
                    reload();
                } catch (IOException e) {
                    toast("建立失敗");
                }
            }
            Ui.button(c, x + 49, by, 22, bh, true, c.hovered(x + 49, by, 22, bh));
            Ui.buttonLabel(c, x + 49, by, 22, bh, "新資料夾", true);

            if (clickOn(x + 73, by, 22, bh)) {
                downloadDialog = true;
                urlInput = "";
            }
            Ui.button(c, x + 73, by, 22, bh, true, c.hovered(x + 73, by, 22, bh));
            Ui.buttonLabel(c, x + 73, by, 22, bh, "下載", true);

            if (clickOn(x + 97, by, 22, bh)) reload();
            Ui.button(c, x + 97, by, 22, bh, true, c.hovered(x + 97, by, 22, bh));
            Ui.buttonLabel(c, x + 97, by, 22, bh, "刷新", true);

            int listY = by + bh + 2;
            int listH = h - (listY - y) - 14;
            int rowH = 12;
            int contentH = entries.size() * rowH;
            scroller.clamp(contentH, listH);
            int off = (int) scroller.offset();

            for (int i = 0; i < entries.size(); i++) {
                int ry = listY + i * rowH - off;
                if (ry + rowH < listY || ry > listY + listH) continue;
                Path p = entries.get(i);
                boolean dir = Files.isDirectory(p);
                String name = p.getFileName().toString();
                boolean hover = c.hovered(x, ry, w, rowH);
                if (hover) Ui.fill(g, x, ry, w, rowH, s.pressedOverlay());

                if (clickOn(x, ry, w - 28, rowH)) openEntry(p);
                if (clickOn(x + w - 28, ry, 14, rowH)) {
                    pendingDelete = name;
                }
                if (clickOn(x + w - 14, ry, 14, rowH)) {
                    // 壓縮資料夾或檔案
                    Path src = p;
                    compressing = true;
                    toast("壓縮中…");
                    Thread t = new Thread(() -> {
                        String msg;
                        try {
                            Path zip = src.resolveSibling(src.getFileName() + ".zip");
                            ArchiveUtil.createZip(src, zip);
                            msg = "已壓縮 → " + zip.getFileName();
                        } catch (IOException e) {
                            msg = "壓縮失敗";
                        }
                        final String m = msg;
                        net.minecraft.client.Minecraft.getInstance().execute(() -> {
                            compressing = false;
                            toast(m);
                            reload();
                        });
                    }, "mcphone-ultra-zip");
                    t.setDaemon(true);
                    t.start();
                }
                Ui.text(c, (dir ? "▸ " : "  ") + truncate(name, 13), x + 3, ry + 1,
                        dir ? s.accentColor() : s.bodyColor());
                Ui.textClipped(c, (dir ? "目錄" : sizeStr(p)) + (ArchiveUtil.isArchive(name) ? " ⚑" : ""),
                        x + 74, ry + 2, s.subtleColor(), x, ry, w, rowH);
                Ui.hline(g, x + 2, x + w - 2, ry + rowH - 1, s.buttonDisabledColor());
            }
            Ui.scrollbar(c, x + w - 3, listY, listH, contentH, off);

            if (clickOn(x + 1, y + h - 13, 60, 12) && cwd.getParent() != null
                    && !cwd.equals(Paths.dir("files"))) {
                cwd = cwd.getParent();
                reload();
            }
            Ui.textClipped(c, "點行=開啟/進入  行尾✕=刪除  ⚑=壓縮包", x + 1, y + h - 12,
                    s.subtleColor(), x, y + h - 13, 80, 12);

            if (pendingDelete != null) {
                int px = x + 6, py = y + 60;
                Ui.fill(g, px, py, w - 12, 44, 0xFF202830);
                Ui.border(c, px, py, w - 12, 44, s.accentColor());
                Ui.textClipped(c, "刪除「" + pendingDelete + "」？", px + 3, py + 3, s.titleColor(), px, py, w - 12, 44);
                if (clickOn(px + 6, py + 24, 40, 12)) {
                    deletePath(cwd.resolve(pendingDelete));
                    pendingDelete = null;
                }
                Ui.button(c, px + 6, py + 24, 40, 12, true, c.hovered(px + 6, py + 24, 40, 12));
                Ui.buttonLabel(c, px + 6, py + 24, 40, 12, "刪除", true);
                if (clickOn(px + 50, py + 24, 40, 12)) pendingDelete = null;
                Ui.button(c, px + 50, py + 24, 40, 12, true, c.hovered(px + 50, py + 24, 40, 12));
                Ui.buttonLabel(c, px + 50, py + 24, 40, 12, "取消", true);
            }

            if (downloadDialog) dialog(c, s, g, "下載網址（Enter 開始）", urlInput, "下載", "取消", () -> {
                doDownload();
            });
            if (passDialog) dialog(c, s, g,
                    "密碼（壓縮檔「" + (pendingExtract == null ? "" : pendingExtract.getFileName()) + "」）",
                    passInput, "解壓", "取消", () -> {
                        passDialog = false;
                        doExtract();
                    });

            renderToast(c, x, y, w, h);
        }

        // ================= 編輯器 =================

        private void renderEditor(PhoneCanvas c, int x, int y, int w, int h, PhoneStyle s, GuiGraphics g) {
            Ui.fill(g, x, y, w, h, s.screenBackground());
            Ui.textClipped(c, (hexMode ? "⬢ " : "📝 ") + editing.getFileName(), x + 3, y + 2,
                    s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            int bh = 12;
            if (clickOn(x + 1, y + 13, 34, bh)) {
                saveEdit();
                editing = null;
            }
            Ui.button(c, x + 1, y + 13, 34, bh, true, c.hovered(x + 1, y + 13, 34, bh));
            Ui.buttonLabel(c, x + 1, y + 13, 34, bh, "← 返回", true);

            if (clickOn(x + 37, y + 13, 34, bh)) saveEdit();
            Ui.button(c, x + 37, y + 13, 34, bh, true, c.hovered(x + 37, y + 13, 34, bh));
            Ui.buttonLabel(c, x + 37, y + 13, 34, bh, "儲存", true);

            if (clickOn(x + 73, y + 13, 46, bh)) {
                saveEdit();
                hexMode = !hexMode;
                if (hexMode) {
                    try {
                        hexBytes = Files.readAllBytes(editing);
                    } catch (IOException e) {
                        hexBytes = new byte[0];
                    }
                }
                hexScroll = 0;
                hexSel = -1;
            }
            Ui.button(c, x + 73, y + 13, 46, bh, true, c.hovered(x + 73, y + 13, 46, bh));
            Ui.buttonLabel(c, x + 73, y + 13, 46, bh, hexMode ? "文字" : "Hex", true);

            if (hexMode) {
                renderHex(c, x, y, w, h, s, g);
            } else {
                int ty = y + 28;
                int th = h - 28 - 14;
                int rows = buffer.render(c, x, ty, w - 2, th, true);
                buffer.scrollV(0, rows);
                renderToast(c, x, y, w, h);
            }
        }

        private void renderHex(PhoneCanvas c, int x, int y, int w, int h, PhoneStyle s, GuiGraphics g) {
            if (hexBytes == null) return;
            int cols = 8;
            int rowH = 9;
            int tx = x + 3, ty = y + 28;
            int rows = (hexBytes.length + cols - 1) / cols;
            int visible = (h - 28 - 14) / rowH;
            if (hexScroll > Math.max(0, rows - visible)) hexScroll = Math.max(0, rows - visible);
            for (int r = hexScroll; r < Math.min(rows, hexScroll + visible); r++) {
                int ry = ty + (r - hexScroll) * rowH;
                int base = r * cols;
                Ui.textClipped(c, String.format("%04X", r * cols), x + 2, ry, s.subtleColor(), x, ty, w, rowH);
                for (int cc = 0; cc < cols; cc++) {
                    int idx = base + cc;
                    if (idx >= hexBytes.length) break;
                    int cx2 = x + 26 + cc * 12;
                    boolean sel = idx == hexSel;
                    if (sel) Ui.fill(g, cx2 - 1, ry, 11, rowH, s.pressedOverlay());
                    if (clickOn(cx2 - 1, ry, 11, rowH)) {
                        hexSel = idx;
                        hexInput = "";
                    }
                    Ui.textClipped(c, String.format("%02X", hexBytes[idx] & 0xFF), cx2, ry,
                            sel ? s.accentColor() : s.bodyColor(), x, ty, w, rowH);
                }
                // ASCII
                StringBuilder ascii = new StringBuilder();
                for (int cc = 0; cc < cols; cc++) {
                    int idx = base + cc;
                    if (idx >= hexBytes.length) break;
                    int b = hexBytes[idx] & 0xFF;
                    ascii.append(b >= 32 && b < 127 ? (char) b : '.');
                }
                Ui.textClipped(c, ascii.toString(), x + w - 34, ry, s.subtleColor(), x, ty, w, rowH);
            }
            // 選中的 byte 編輯提示
            if (hexSel >= 0 && hexSel < hexBytes.length) {
                String cur = String.format("byte[%d]=%02X  輸入 0-9A-F（Enter 套用）",
                        hexSel, hexBytes[hexSel] & 0xFF);
                Ui.textClipped(c, cur, x + 2, y + h - 12, s.accentColor(), x, y + h - 12, w, 12);
            }
        }

        private void applyHexChar(char ch) {
            if (hexSel < 0 || hexSel >= hexBytes.length) return;
            char up = Character.toUpperCase(ch);
            if ("0123456789ABCDEF".indexOf(up) < 0) return;
            hexInput += up;
            if (hexInput.length() >= 2) {
                try {
                    hexBytes[hexSel] = (byte) Integer.parseInt(hexInput, 16);
                } catch (NumberFormatException ignored) {
                }
                hexInput = "";
                hexSel = Math.min(hexBytes.length - 1, hexSel + 1);
            }
        }

        // ================= 對話框 =================

        private interface Action {
            void run();
        }

        private void dialog(PhoneCanvas c, PhoneStyle s, GuiGraphics g,
                            String label, String value, String ok, String cancel, Action onOk) {
            int x = c.x(), y = c.y(), w = c.width(), h = c.height();
            int px = x + 6, py = y + 46;
            Ui.fill(g, px, py, w - 12, 58, 0xFF202830);
            Ui.border(c, px, py, w - 12, 58, s.accentColor());
            Ui.textClipped(c, label, px + 3, py + 2, s.titleColor(), px, py, w - 12, 58);
            Ui.fill(g, px + 3, py + 15, w - 18, 12, 0xFF000000);
            Ui.textClipped(c, value + (System.currentTimeMillis() / 500 % 2 == 0 ? "▏" : ""),
                    px + 5, py + 16, s.bodyColor(), px, py, w - 12, 58);
            if (clickOn(px + 3, py + 31, 50, 12)) {
                if (downloadDialog) downloadDialog = false;
                if (passDialog) passDialog = false;
                onOk.run();
            }
            Ui.button(c, px + 3, py + 31, 50, 12, true, c.hovered(px + 3, py + 31, 50, 12));
            Ui.buttonLabel(c, px + 3, py + 31, 50, 12, ok, true);
            if (clickOn(px + 57, py + 31, 50, 12)) {
                downloadDialog = false;
                passDialog = false;
            }
            Ui.button(c, px + 57, py + 31, 50, 12, true, c.hovered(px + 57, py + 31, 50, 12));
            Ui.buttonLabel(c, px + 57, py + 31, 50, 12, cancel, true);
        }

        // ================= 輸入 =================

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (editing != null) {
                if (!hexMode) {
                    buffer.onKey(key, scan, mods);
                    if (key == GLFW.GLFW_KEY_S && (mods & 2) != 0) saveEdit();
                } else if (key == GLFW.GLFW_KEY_BACKSPACE && hexSel >= 0 && !hexInput.isEmpty()) {
                    hexInput = hexInput.substring(0, hexInput.length() - 1);
                } else if (key == GLFW.GLFW_KEY_UP) {
                    hexScroll = Math.max(0, hexScroll - 1);
                } else if (key == GLFW.GLFW_KEY_DOWN) {
                    hexScroll++;
                } else if (key == GLFW.GLFW_KEY_LEFT && hexSel > 0) {
                    hexSel--;
                } else if (key == GLFW.GLFW_KEY_RIGHT && hexSel < hexBytes.length - 1) {
                    hexSel++;
                }
                return true;
            }
            if (downloadDialog) {
                if (key == GLFW.GLFW_KEY_ENTER) {
                    downloadDialog = false;
                    doDownload();
                } else if (key == GLFW.GLFW_KEY_BACKSPACE && !urlInput.isEmpty()) {
                    urlInput = urlInput.substring(0, urlInput.length() - 1);
                }
                return true;
            }
            if (passDialog) {
                if (key == GLFW.GLFW_KEY_ENTER) {
                    passDialog = false;
                    doExtract();
                } else if (key == GLFW.GLFW_KEY_BACKSPACE && !passInput.isEmpty()) {
                    passInput = passInput.substring(0, passInput.length() - 1);
                }
                return true;
            }
            return false;
        }

        @Override
        public boolean charTyped(char ch, int mods) {
            if (editing != null) {
                if (!hexMode) {
                    buffer.insert(ch);
                } else {
                    applyHexChar(ch);
                }
                return true;
            }
            if (downloadDialog) {
                if (ch >= 32 && ch != 127) urlInput += ch;
                return true;
            }
            if (passDialog) {
                if (ch >= 32 && ch != 127) passInput += ch;
                return true;
            }
            return false;
        }

        @Override
        public boolean capturesKeyboard() {
            return editing != null || downloadDialog || passDialog;
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            if (editing != null) {
                if (hexMode) {
                    hexScroll = Math.max(0, hexScroll + (amount > 0 ? -1 : 1));
                }
                return true;
            }
            scroller.onWheel(amount, entries.size() * 12, 130);
            return true;
        }

        @Override
        public boolean onBack() {
            if (editing != null) {
                saveEdit();
                editing = null;
                return true;
            }
            if (downloadDialog) {
                downloadDialog = false;
                return true;
            }
            if (passDialog) {
                passDialog = false;
                return true;
            }
            if (pendingDelete != null) {
                pendingDelete = null;
                return true;
            }
            if (cwd.getParent() != null && Files.isDirectory(cwd.getParent())) {
                cwd = cwd.getParent();
                reload();
                return true;
            }
            return false;
        }

        // ================= 工具 =================

        private static String truncate(String s, int n) {
            if (s.length() <= n) return s;
            return s.substring(0, n - 1) + "…";
        }

        private static String sizeStr(Path p) {
            try {
                long len = Files.size(p);
                if (len < 1024) return len + "B";
                if (len < 1024 * 1024) return (len / 1024) + "KB";
                return (len / 1024 / 1024) + "MB";
            } catch (IOException e) {
                return "";
            }
        }

        private void renderToast(PhoneCanvas c, int x, int y, int w, int h) {
            if (System.currentTimeMillis() < toastUntil && !toast.isEmpty()) {
                int tw = c.font().width(toast) + 8;
                int tx = x + (w - tw) / 2;
                int ty = y + h - 20;
                Ui.fill(c.graphics(), tx, ty, tw, 12, 0xE0202830);
                Ui.text(c, toast, tx + 4, ty + 1, c.style().titleColor());
            }
        }
    }
}
