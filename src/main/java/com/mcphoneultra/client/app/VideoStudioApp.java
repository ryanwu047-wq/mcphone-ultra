package com.mcphoneultra.client.app;

import com.google.gson.JsonObject;
import com.mcphoneultra.client.io.Paths;
import com.mcphoneultra.client.io.Store;
import com.mcphoneultra.client.ui.Scroller;
import com.mcphoneultra.client.ui.Ui;
import com.mcphoneultra.client.util.Exec;
import com.mcphoneultra.client.util.Images;
import com.mcphoneultra.client.util.Images.Tex;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.gui.GuiGraphics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 影片編輯 + 觀看。
 *
 * 「影片」= videos/ 下每个子文件夹：里面是一串按文件名排序的帧图（PNG/JPG）+ 可选的 project.json
 * （fps、名称）。播放器按 fps 逐帧放映；编辑器做标记裁剪、合并、变速；还能从游戏截图或
 * （装有 ffmpeg 时）从真实视频文件抽帧生成影片。
 */
public final class VideoStudioApp extends BaseApp {

    private IPhonePage page;

    public VideoStudioApp() {
        super("videostudio", true);
    }

    @Override
    protected IPhonePage createPage() {
        if (page == null) page = new LibraryPage(this);
        return page;
    }

    /** 页内跳转：库 ↔ 播放 ↔ 编辑 */
    void show(IPhonePage p) {
        page = p;
    }

    // ---------------- 数据层 ----------------

    static final class VideoStore {

        static Path videosDir() {
            return Paths.dir("videos");
        }

        static List<String> listClips() {
            List<String> out = new ArrayList<>();
            try (var stream = Files.list(videosDir())) {
                stream.filter(Files::isDirectory).forEach(p -> out.add(p.getFileName().toString()));
            } catch (IOException ignored) {
            }
            out.sort(String::compareTo);
            return out;
        }

        static Path clipDir(String name) {
            return videosDir().resolve(Paths.safeName(name));
        }

        static List<Path> framesOf(Path clip) {
            List<Path> out = new ArrayList<>();
            if (clip == null || !Files.isDirectory(clip)) return out;
            try (var stream = Files.list(clip)) {
                stream.filter(p -> isFrame(p.getFileName().toString()))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .forEach(out::add);
            } catch (IOException ignored) {
            }
            return out;
        }

        static boolean isFrame(String name) {
            String n = name.toLowerCase(Locale.ROOT);
            return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".bmp");
        }

        static int readFps(Path clip, int def) {
            Path pf = clip.resolve("project.json");
            try {
                JsonObject o = com.google.gson.JsonParser.parseString(Files.readString(pf)).getAsJsonObject();
                return o.has("fps") ? o.get("fps").getAsInt() : def;
            } catch (Exception e) {
                return def;
            }
        }

        static void writeFps(Path clip, int fps) {
            Path pf = clip.resolve("project.json");
            try {
                JsonObject o;
                if (Files.isRegularFile(pf)) {
                    o = com.google.gson.JsonParser.parseString(Files.readString(pf)).getAsJsonObject();
                } else {
                    o = new JsonObject();
                }
                o.addProperty("fps", fps);
                Files.writeString(pf, o.toString());
            } catch (IOException ignored) {
            }
        }

        /** 从游戏截图目录把所有 PNG 复制进新影片，重新编号。 */
        static String importScreenshots(String name) {
            Path shots = Paths.file("..", "screenshots").getParent().resolve("screenshots");
            if (!Files.isDirectory(shots)) shots = java.nio.file.Paths.get("screenshots");
            Path src = Files.isDirectory(shots) ? shots : null;
            if (src == null) return "找不到遊戲截圖目錄";
            List<Path> imgs = new ArrayList<>();
            try (var stream = Files.list(src)) {
                stream.filter(p -> isFrame(p.getFileName().toString())).forEach(imgs::add);
            } catch (IOException e) {
                return "讀取截圖失敗";
            }
            imgs.sort(Comparator.comparing(p -> p.getFileName().toString()));
            if (imgs.isEmpty()) return "截圖目錄裡沒有圖片";
            String safe = Paths.safeName(name);
            Path out = clipDir(safe);
            try {
                Files.createDirectories(out);
                int n = 0;
                for (Path img : imgs) {
                    n++;
                    Files.copy(img, out.resolve(String.format("frame_%04d.png", n)),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                return "複製失敗";
            }
            return null;
        }

        /** 用 ffmpeg 把 videos/_import 下第一个视频文件抽成帧。 */
        static String ffmpegExtract(String name) {
            String ff = Exec.ffmpegPath();
            if (ff.isEmpty()) return "未找到 ffmpeg（請安裝並加入 PATH）";
            Path imp = videosDir().resolve("_import");
            try {
                Files.createDirectories(imp);
            } catch (IOException e) {
                return "無法建立 _import 目錄";
            }
            Path video = null;
            try (var stream = Files.list(imp)) {
                var list = stream.filter(p -> !Files.isDirectory(p)).toList();
                if (!list.isEmpty()) video = list.get(0);
            } catch (IOException e) {
                return "讀取 _import 失敗";
            }
            if (video == null) return "請先把影片檔放進 videos/_import/";
            String safe = Paths.safeName(name);
            Path out = clipDir(safe);
            try {
                Files.createDirectories(out);
            } catch (IOException e) {
                return "建立目錄失敗";
            }
            Exec.Result r = Exec.run(ff, "-y", "-i", com.mcphoneultra.client.util.Exec.norm(video),
                    com.mcphoneultra.client.util.Exec.norm(out.resolve("frame_%04d.png")));
            if (r.code() != 0) {
                return "抽幀失敗：" + (r.stderr().isEmpty() ? "未知錯誤" : r.stderr().split("\n")[0]);
            }
            return null;
        }

        /** 标记裁剪：把 [start, end] 帧复制到新影片。 */
        static String trimClip(Path clip, String name, int start, int end) {
            List<Path> frames = framesOf(clip);
            if (frames.isEmpty()) return "沒有幀";
            start = Math.max(0, Math.min(start, frames.size() - 1));
            end = Math.max(start, Math.min(end, frames.size()));
            String safe = Paths.safeName(name);
            Path out = clipDir(safe);
            try {
                Files.createDirectories(out);
                int n = 0;
                for (int i = start; i < end; i++) {
                    n++;
                    Files.copy(frames.get(i), out.resolve(String.format("frame_%04d.png", n)),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                return "裁剪失敗";
            }
            return null;
        }

        /** 合并多个影片（按顺序串帧）。 */
        static String mergeClips(List<Path> clips, String name) {
            List<Path> all = new ArrayList<>();
            for (Path clip : clips) all.addAll(framesOf(clip));
            if (all.isEmpty()) return "沒有可合併的幀";
            String safe = Paths.safeName(name);
            Path out = clipDir(safe);
            try {
                Files.createDirectories(out);
                int n = 0;
                for (Path f : all) {
                    n++;
                    Files.copy(f, out.resolve(String.format("frame_%04d.png", n)),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                return "合併失敗";
            }
            return null;
        }
    }

    // ---------------- 帧贴图缓存 ----------------

    static final class FrameCache {
        private final Map<String, Tex> cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Tex> eldest) {
                if (size() > 10) {
                    Images.release(eldest.getValue());
                    return true;
                }
                return false;
            }
        };

        Tex get(Path p) {
            String key = p.toString();
            Tex t = cache.get(key);
            if (t != null) return t;
            var img = Images.readAny(p);
            if (img == null) return null;
            t = Images.upload(img, "video");
            cache.put(key, t);
            return t;
        }

        void clear() {
            cache.values().forEach(Images::release);
            cache.clear();
        }
    }

    // ---------------- 库页 ----------------

    private static final class LibraryPage extends ClickablePage {

        private final VideoStudioApp app;
        private final Scroller scroller = new Scroller();

        LibraryPage(VideoStudioApp app) {
            this.app = app;
        }
        private List<String> clips = new ArrayList<>();
        private String toast = "";
        private long toastUntil;
        private String pendingDelete;
        private String newName = "";
        private boolean naming;
        private String namingFor;   // "new" | "screens" | "ffmpeg"
        private String busy;

        private void toast(String s) {
            toast = s;
            toastUntil = System.currentTimeMillis() + 2200;
        }

        private void reload() {
            clips = VideoStore.listClips();
            scroller.reset();
        }

        @Override
        public void onOpen() {
            reload();
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

            Ui.text(c, "🎬 影片工作室", x + 3, y + 2, s.titleColor());
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            int by = y + 13;
            int bh = 12;
            int bw = (w - 6) / 3;
            if (clickOn(x + 1, by, bw, bh)) {
                naming = true;
                namingFor = "screens";
                newName = "影片" + (clips.size() + 1);
            }
            Ui.button(c, x + 1, by, bw, bh, true, c.hovered(x + 1, by, bw, bh));
            Ui.buttonLabel(c, x + 1, by, bw, bh, "截圖匯入", true);
            if (clickOn(x + 2 + bw, by, bw, bh)) {
                naming = true;
                namingFor = "ffmpeg";
                newName = "抽幀" + (clips.size() + 1);
            }
            Ui.button(c, x + 2 + bw, by, bw, bh, true, c.hovered(x + 2 + bw, by, bw, bh));
            Ui.buttonLabel(c, x + 2 + bw, by, bw, bh, "ffmpeg抽幀", true);
            if (clickOn(x + 3 + bw * 2, by, bw, bh)) {
                naming = true;
                namingFor = "new";
                newName = "空白" + (clips.size() + 1);
            }
            Ui.button(c, x + 3 + bw * 2, by, bw, bh, true, c.hovered(x + 3 + bw * 2, by, bw, bh));
            Ui.buttonLabel(c, x + 3 + bw * 2, by, bw, bh, "新空白", true);

            int listY = by + bh + 2;
            int listH = h - (listY - y) - 14;
            int rowH = 24;
            int contentH = clips.size() * rowH;
            scroller.clamp(contentH, listH);
            int off = (int) scroller.offset();

            for (int i = 0; i < clips.size(); i++) {
                int ry = listY + i * rowH - off;
                if (ry + rowH < listY || ry > listY + listH) continue;
                String name = clips.get(i);
                Path dir = VideoStore.clipDir(name);
                int frames = VideoStore.framesOf(dir).size();
                int fps = VideoStore.readFps(dir, 10);
                if (c.hovered(x, ry, w, rowH)) Ui.fill(g, x, ry, w, rowH, s.pressedOverlay());

                Ui.textClipped(c, trunc(name, 12), x + 3, ry + 1, s.titleColor(), x, ry, w, rowH);
                Ui.textClipped(c, frames + " 幀 · " + fps + " fps", x + 3, ry + 10, s.subtleColor(), x, ry, w, rowH);

                int bwb = 26;
                if (clickOn(x + w - 82, ry + 6, bwb, 12) && frames > 0) {
                    openPlayer(name);
                }
                Ui.button(c, x + w - 82, ry + 6, bwb, 12, frames > 0, c.hovered(x + w - 82, ry + 6, bwb, 12));
                Ui.buttonLabel(c, x + w - 82, ry + 6, bwb, 12, "播放", frames > 0);
                if (clickOn(x + w - 54, ry + 6, bwb, 12)) {
                    openEditor(name);
                }
                Ui.button(c, x + w - 54, ry + 6, bwb, 12, true, c.hovered(x + w - 54, ry + 6, bwb, 12));
                Ui.buttonLabel(c, x + w - 54, ry + 6, bwb, 12, "編輯", true);
                if (clickOn(x + w - 26, ry + 6, bwb, 12)) {
                    pendingDelete = name;
                    toast("確認刪除？");
                }
                Ui.button(c, x + w - 26, ry + 6, bwb, 12, true, c.hovered(x + w - 26, ry + 6, bwb, 12));
                Ui.buttonLabel(c, x + w - 26, ry + 6, bwb, 12, "刪", true);
            }
            Ui.scrollbar(c, x + w - 3, listY, listH, contentH, off);

            if (pendingDelete != null) {
                int px = x + 6;
                int py = y + 60;
                Ui.fill(g, px, py, w - 12, 44, 0xFF202830);
                Ui.border(c, px, py, w - 12, 44, s.accentColor());
                Ui.textClipped(c, "刪除「" + pendingDelete + "」？", px + 3, py + 3, s.titleColor(), px, py, w - 12, 44);
                if (clickOn(px + 6, py + 24, 40, 12)) {
                    deleteClip(pendingDelete);
                    pendingDelete = null;
                    reload();
                }
                Ui.button(c, px + 6, py + 24, 40, 12, true, c.hovered(px + 6, py + 24, 40, 12));
                Ui.buttonLabel(c, px + 6, py + 24, 40, 12, "刪除", true);
                if (clickOn(px + 50, py + 24, 40, 12)) {
                    pendingDelete = null;
                }
                Ui.button(c, px + 50, py + 24, 40, 12, true, c.hovered(px + 50, py + 24, 40, 12));
                Ui.buttonLabel(c, px + 50, py + 24, 40, 12, "取消", true);
            }

            if (naming) {
                int px = x + 6;
                int py = y + 70;
                Ui.fill(g, px, py, w - 12, 56, 0xFF202830);
                Ui.border(c, px, py, w - 12, 56, s.accentColor());
                Ui.text(c, "影片名稱（Enter 確定）", px + 3, py + 3, s.titleColor());
                Ui.fill(g, px + 3, py + 16, w - 18, 12, 0xFF000000);
                Ui.textClipped(c, newName + (System.currentTimeMillis() / 500 % 2 == 0 ? "▏" : ""),
                        px + 5, py + 17, s.bodyColor(), px, py, w - 12, 56);
                if (clickOn(px + 3, py + 32, 50, 12)) {
                    doCreate();
                }
                Ui.button(c, px + 3, py + 32, 50, 12, true, c.hovered(px + 3, py + 32, 50, 12));
                Ui.buttonLabel(c, px + 3, py + 32, 50, 12, "確定", true);
                if (clickOn(px + 57, py + 32, 50, 12)) {
                    naming = false;
                }
                Ui.button(c, px + 57, py + 32, 50, 12, true, c.hovered(px + 57, py + 32, 50, 12));
                Ui.buttonLabel(c, px + 57, py + 32, 50, 12, "取消", true);
            }

            if (busy != null) {
                Ui.text(c, busy, x + 4, y + h - 16, s.accentColor());
            }

            renderToast(c, x, y, w, h);
        }

        private void doCreate() {
            naming = false;
            String name = newName.trim();
            if (name.isEmpty()) return;
            String err;
            switch (namingFor) {
                case "screens" -> {
                    busy = "匯入中…";
                    err = VideoStore.importScreenshots(name);
                    busy = null;
                }
                case "ffmpeg" -> {
                    busy = "抽幀中…";
                    err = VideoStore.ffmpegExtract(name);
                    busy = null;
                }
                default -> {
                    try {
                        Files.createDirectories(VideoStore.clipDir(name));
                        err = null;
                    } catch (IOException e) {
                        err = "建立失敗";
                    }
                }
            }
            if (err != null) toast(err);
            else toast("完成");
            reload();
        }

        private void deleteClip(String name) {
            Path dir = VideoStore.clipDir(name);
            try (var stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            } catch (IOException ignored) {
            }
        }

        private void openPlayer(String name) {
            app.show(new PlayerPage(app, name));
        }

        private void openEditor(String name) {
            app.show(new EditorPage(app, name));
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            scroller.onWheel(amount, clips.size() * 24, 130);
            return true;
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (naming && key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER) {
                doCreate();
                return true;
            }
            if (naming && key == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE && !newName.isEmpty()) {
                newName = newName.substring(0, newName.length() - 1);
                return true;
            }
            if (naming) return true;
            return false;
        }

        @Override
        public boolean charTyped(char ch, int mods) {
            if (naming) {
                if (ch >= 32 && ch != 127) newName += ch;
                return true;
            }
            return false;
        }

        @Override
        public boolean capturesKeyboard() {
            return naming;
        }

        @Override
        public boolean onBack() {
            if (naming) {
                naming = false;
                return true;
            }
            if (pendingDelete != null) {
                pendingDelete = null;
                return true;
            }
            return false;
        }

        private static String trunc(String s, int n) {
            return s.length() <= n ? s : s.substring(0, n - 1) + "…";
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

    // ---------------- 播放页 ----------------

    private static final class PlayerPage extends ClickablePage {

        private final VideoStudioApp app;
        private final String clipName;
        private final List<Path> frames;
        private final FrameCache cache = new FrameCache();
        private int index;
        private boolean playing = true;
        private int fps;
        private long lastAdvance;
        private boolean disposed;

        PlayerPage(VideoStudioApp app, String clipName) {
            this.app = app;
            this.clipName = clipName;
            Path dir = VideoStore.clipDir(clipName);
            this.frames = VideoStore.framesOf(dir);
            this.fps = VideoStore.readFps(dir, 10);
        }

        @Override
        public void onOpen() {
            lastAdvance = System.currentTimeMillis();
        }

        @Override
        public void onClose() {
            disposed = true;
            cache.clear();
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

            Ui.textClipped(c, "▶ " + clipName, x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            // 自动播放
            if (playing && !frames.isEmpty()) {
                long now = System.currentTimeMillis();
                long interval = 1000 / Math.max(1, fps);
                while (now - lastAdvance >= interval) {
                    lastAdvance += interval;
                    index++;
                    if (index >= frames.size()) index = 0;
                }
            }

            int boxY = y + 14;
            int boxH = h - 58;
            Ui.border(c, x + 1, boxY, w - 2, boxH, s.buttonDisabledColor());
            if (index < frames.size()) {
                Tex tex = cache.get(frames.get(index));
                if (tex != null) {
                    int boxW = w - 6;
                    int bh2 = boxH - 2;
                    float scale = Math.min(boxW / (float) tex.w(), bh2 / (float) tex.h());
                    int dw = Math.max(1, (int) (tex.w() * scale));
                    int dh = Math.max(1, (int) (tex.h() * scale));
                    g.blit(tex.loc(), x + 1 + (w - 2 - dw) / 2, boxY + 1 + (bh2 - dh) / 2,
                            dw, dh, 0, 0, tex.w(), tex.h(), tex.w(), tex.h());
                } else {
                    Ui.drawCentered(c, "（無法解碼此幀）", x, boxY, w, boxH, s.subtleColor());
                }
            } else {
                Ui.drawCentered(c, "（沒有幀）", x, boxY, w, boxH, s.subtleColor());
            }

            // 帧号 / 时间
            Ui.text(c, "幀 " + (index + 1) + " / " + frames.size() + "  ·  " + fps + "fps",
                    x + 3, boxY + boxH - 10, s.subtleColor());

            // 传输控制
            int by = y + h - 40;
            int bh = 12;
            if (clickOn(x + 3, by, 26, bh)) {
                if (fps > 2) fps -= 2;
            }
            Ui.button(c, x + 3, by, 26, bh, true, c.hovered(x + 3, by, 26, bh));
            Ui.buttonLabel(c, x + 3, by, 26, bh, "慢", true);

            if (clickOn(x + 32, by, 24, bh)) {
                playing = !playing;
            }
            Ui.button(c, x + 32, by, 24, bh, true, c.hovered(x + 32, by, 24, bh));
            Ui.buttonLabel(c, x + 32, by, 24, bh, playing ? "⏸" : "▶", true);

            if (clickOn(x + 59, by, 24, bh)) {
                if (fps < 30) fps += 2;
            }
            Ui.button(c, x + 59, by, 24, bh, true, c.hovered(x + 59, by, 24, bh));
            Ui.buttonLabel(c, x + 59, by, 24, bh, "快", true);

            if (clickOn(x + 86, by, w - 88, bh)) {
                app.show(new LibraryPage(app));
            }
            Ui.button(c, x + 86, by, w - 88, bh, true, c.hovered(x + 86, by, w - 88, bh));
            Ui.buttonLabel(c, x + 86, by, w - 88, bh, "← 返回", true);

            // 帧导航
            int ny = by + bh + 2;
            if (clickOn(x + 3, ny, 28, bh)) {
                index = Math.max(0, index - 1);
                playing = false;
                lastAdvance = System.currentTimeMillis();
            }
            Ui.button(c, x + 3, ny, 28, bh, true, c.hovered(x + 3, ny, 28, bh));
            Ui.buttonLabel(c, x + 3, ny, 28, bh, "◀", true);
            if (clickOn(x + 34, ny, 28, bh)) {
                index = Math.min(frames.size() - 1, index + 1);
                playing = false;
                lastAdvance = System.currentTimeMillis();
            }
            Ui.button(c, x + 34, ny, 28, bh, true, c.hovered(x + 34, ny, 28, bh));
            Ui.buttonLabel(c, x + 34, ny, 28, bh, "▶", true);
            if (clickOn(x + 65, ny, w - 67, bh)) {
                app.show(new EditorPage(app, clipName));
            }
            Ui.button(c, x + 65, ny, w - 67, bh, true, c.hovered(x + 65, ny, w - 67, bh));
            Ui.buttonLabel(c, x + 65, ny, w - 67, bh, "前往編輯", true);
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            int delta = (int) -amount;
            index = Math.max(0, Math.min(frames.size() - 1, index + delta));
            playing = false;
            return true;
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE) {
                playing = !playing;
                return true;
            }
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT) {
                index = Math.max(0, index - 1);
                playing = false;
                return true;
            }
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT) {
                index = Math.min(frames.size() - 1, index + 1);
                playing = false;
                return true;
            }
            return false;
        }

        @Override
        public boolean onBack() {
            app.show(new LibraryPage(app));
            return true;
        }
    }

    // ---------------- 编辑页 ----------------

    private static final class EditorPage extends ClickablePage {

        private final VideoStudioApp app;
        private final String clipName;
        private final List<Path> frames;
        private final FrameCache cache = new FrameCache();
        private int index;
        private int markIn = -1;
        private int markOut = -1;
        private int fps;
        private boolean disposed;
        private String toast = "";
        private long toastUntil;
        private String newName;
        private boolean namingMerge;
        private List<String> clipsAll = new ArrayList<>();

        EditorPage(VideoStudioApp app, String clipName) {
            this.app = app;
            this.clipName = clipName;
            Path dir = VideoStore.clipDir(clipName);
            this.frames = VideoStore.framesOf(dir);
            this.fps = VideoStore.readFps(dir, 10);
        }

        @Override
        public void onOpen() {
            clipsAll = VideoStore.listClips();
        }

        @Override
        public void onClose() {
            disposed = true;
            cache.clear();
        }

        private void toast(String s) {
            toast = s;
            toastUntil = System.currentTimeMillis() + 2200;
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

            Ui.textClipped(c, "✂ 編輯：" + clipName, x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            int boxY = y + 14;
            int boxH = h - 96;
            Ui.border(c, x + 1, boxY, w - 2, boxH, s.buttonDisabledColor());
            if (index < frames.size()) {
                Tex tex = cache.get(frames.get(index));
                if (tex != null) {
                    int boxW = w - 6;
                    int bh2 = boxH - 2;
                    float scale = Math.min(boxW / (float) tex.w(), bh2 / (float) tex.h());
                    int dw = Math.max(1, (int) (tex.w() * scale));
                    int dh = Math.max(1, (int) (tex.h() * scale));
                    g.blit(tex.loc(), x + 1 + (w - 2 - dw) / 2, boxY + 1 + (bh2 - dh) / 2,
                            dw, dh, 0, 0, tex.w(), tex.h(), tex.w(), tex.h());
                }
            }
            Ui.text(c, "幀 " + (index + 1) + "/" + frames.size(), x + 3, boxY + boxH - 10, s.subtleColor());
            String markInfo = (markIn < 0 ? "起:—" : "起:" + (markIn + 1)) + " "
                    + (markOut < 0 ? "止:—" : "止:" + markOut);
            Ui.textClipped(c, markInfo, x + w - 56, boxY + boxH - 10, s.accentColor(), x, boxY, w, boxH);

            int by = y + h - 78;
            int bh = 12;
            int bw = (w - 8) / 4;
            if (clickOn(x + 1, by, bw, bh)) {
                index = Math.max(0, index - 1);
            }
            Ui.button(c, x + 1, by, bw, bh, true, c.hovered(x + 1, by, bw, bh));
            Ui.buttonLabel(c, x + 1, by, bw, bh, "◀ 幀", true);
            if (clickOn(x + 2 + bw, by, bw, bh)) {
                index = Math.min(frames.size() - 1, index + 1);
            }
            Ui.button(c, x + 2 + bw, by, bw, bh, true, c.hovered(x + 2 + bw, by, bw, bh));
            Ui.buttonLabel(c, x + 2 + bw, by, bw, bh, "幀 ▶", true);
            if (clickOn(x + 3 + bw * 2, by, bw, bh)) {
                markIn = index;
            }
            Ui.button(c, x + 3 + bw * 2, by, bw, bh, true, c.hovered(x + 3 + bw * 2, by, bw, bh));
            Ui.buttonLabel(c, x + 3 + bw * 2, by, bw, bh, "標記起", true);
            if (clickOn(x + 4 + bw * 3, by, bw, bh)) {
                markOut = index + 1;
            }
            Ui.button(c, x + 4 + bw * 3, by, bw, bh, true, c.hovered(x + 4 + bw * 3, by, bw, bh));
            Ui.buttonLabel(c, x + 4 + bw * 3, by, bw, bh, "標記止", true);

            int by2 = by + bh + 2;
            if (clickOn(x + 1, by2, bw, bh)) {
                int a = Math.max(0, markIn < 0 ? 0 : markIn);
                int b = Math.max(a + 1, markOut < 0 ? frames.size() : markOut);
                String err = VideoStore.trimClip(VideoStore.clipDir(clipName), clipName + "_剪", a, b);
                toast(err == null ? "已輸出「" + clipName + "_剪」" : err);
                clipsAll = VideoStore.listClips();
            }
            Ui.button(c, x + 1, by2, bw, bh, true, c.hovered(x + 1, by2, bw, bh));
            Ui.buttonLabel(c, x + 1, by2, bw, bh, "套用裁剪", true);
            if (clickOn(x + 2 + bw, by2, bw, bh)) {
                namingMerge = true;
                newName = clipName + "_合併";
            }
            Ui.button(c, x + 2 + bw, by2, bw, bh, true, c.hovered(x + 2 + bw, by2, bw, bh));
            Ui.buttonLabel(c, x + 2 + bw, by2, bw, bh, "合併", true);
            if (clickOn(x + 3 + bw * 2, by2, bw, bh)) {
                int newFps = fps >= 15 ? 5 : fps + 5;
                fps = newFps;
                VideoStore.writeFps(VideoStore.clipDir(clipName), fps);
                toast("fps → " + fps);
            }
            Ui.button(c, x + 3 + bw * 2, by2, bw, bh, true, c.hovered(x + 3 + bw * 2, by2, bw, bh));
            Ui.buttonLabel(c, x + 3 + bw * 2, by2, bw, bh, "fps+" + fps, true);
            if (clickOn(x + 4 + bw * 3, by2, bw, bh)) {
                app.show(new LibraryPage(app));
            }
            Ui.button(c, x + 4 + bw * 3, by2, bw, bh, true, c.hovered(x + 4 + bw * 3, by2, bw, bh));
            Ui.buttonLabel(c, x + 4 + bw * 3, by2, bw, bh, "← 返回", true);

            int by3 = by2 + bh + 2;
            Ui.text(c, "滾輪 = 逐幀；合併 = 與另一部影片串幀", x + 3, by3, s.subtleColor());

            if (namingMerge) {
                int px = x + 4;
                int py = y + 30;
                Ui.fill(g, px, py, w - 8, 78, 0xFF202830);
                Ui.border(c, px, py, w - 8, 78, s.accentColor());
                Ui.text(c, "選擇要合併的影片：", px + 3, py + 3, s.titleColor());
                int rowY = py + 16;
                int shown = 0;
                for (String n : clipsAll) {
                    if (n.equals(clipName)) continue;
                    if (rowY + 11 > py + 78) break;
                    if (clickOn(px + 3, rowY, w - 14, 11)) {
                        String err = VideoStore.mergeClips(
                                List.of(VideoStore.clipDir(clipName), VideoStore.clipDir(n)), newName);
                        toast(err == null ? "已合併為「" + newName + "」" : err);
                        namingMerge = false;
                        clipsAll = VideoStore.listClips();
                        break;
                    }
                    if (c.hovered(px + 3, rowY, w - 14, 11)) Ui.fill(g, px + 3, rowY, w - 14, 11, s.pressedOverlay());
                    Ui.textClipped(c, "▸ " + n, px + 5, rowY, s.bodyColor(), px, py, w - 8, 78);
                    rowY += 11;
                    shown++;
                }
                if (shown == 0) Ui.text(c, "（沒有其他影片）", px + 3, rowY, s.subtleColor());
                if (clickOn(px + 3, py + 64, 50, 11)) {
                    namingMerge = false;
                }
                Ui.button(c, px + 3, py + 64, 50, 11, true, c.hovered(px + 3, py + 64, 50, 11));
                Ui.buttonLabel(c, px + 3, py + 64, 50, 11, "取消", true);
            }

            if (System.currentTimeMillis() < toastUntil && !toast.isEmpty()) {
                Ui.text(c, toast, x + 4, y + h - 14, s.accentColor());
            }
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            int delta = (int) -amount;
            index = Math.max(0, Math.min(frames.size() - 1, index + delta));
            return true;
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT) {
                index = Math.max(0, index - 1);
                return true;
            }
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT) {
                index = Math.min(frames.size() - 1, index + 1);
                return true;
            }
            return false;
        }

        @Override
        public boolean onBack() {
            app.show(new LibraryPage(app));
            return true;
        }
    }
}
