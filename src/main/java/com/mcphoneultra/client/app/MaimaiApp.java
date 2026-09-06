package com.mcphoneultra.client.app;

import com.mcphoneultra.client.audio.AudioEngine;
import com.mcphoneultra.client.io.Paths;
import com.mcphoneultra.client.ui.Scroller;
import com.mcphoneultra.client.ui.Ui;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * maimai 音遊：支援正版 maidata.txt 譜面（&inote_X）匯入（放 files/maimai/&lt;譜名&gt;/ 即可）、
 * 內建 RE Aoharu 示範譜、自製譜面編輯器（8 鍵時間線）、連結 music 資料夾與音樂編輯。
 */
public final class MaimaiApp extends BaseApp {

    public MaimaiApp() {
        super("maimai", true);
    }

    @Override
    protected IPhonePage createPage() {
        return new MaimaiPage();
    }

    // ---------------- 譜面模型 ----------------

    private static final class TapNote {
        double time;
        int key;        // 1..8
        boolean isBreak;

        TapNote(double t, int k, boolean b) {
            time = t;
            key = k;
            isBreak = b;
        }
    }

    private static final class HoldNote {
        double time;
        int key;
        double endTime;
        boolean judgedStart;
        boolean judgedOk;

        HoldNote(double t, int k, double e) {
            time = t;
            key = k;
            endTime = e;
        }
    }

    private static final class Chart {
        String name = "";
        String title = "";
        String artist = "";
        double bpm = 180;
        int level;
        List<TapNote> taps = new ArrayList<>();
        List<HoldNote> holds = new ArrayList<>();
        double lengthSec;
    }

    // ---------------- 解析器 ----------------

    private static final Pattern INOTE = Pattern.compile("^&inote_(\\d+)=(.*)$", Pattern.DOTALL);

    private static Chart parseChart(Path dir, String inoteBody, int level) {
        Chart c = new Chart();
        c.name = dir.getFileName().toString();
        c.level = level;
        String body = inoteBody;
        Matcher bpmM = Pattern.compile("^\\((\\d+)\\)").matcher(body);
        double bpm = 180;
        if (bpmM.find()) {
            bpm = Integer.parseInt(bpmM.group(1));
            body = body.substring(bpmM.end());
        }
        c.bpm = bpm;
        parseInote(body, c);
        c.lengthSec = c.taps.stream().mapToDouble(t -> t.time).max().orElse(0);
        c.holds.forEach(h -> c.lengthSec = Math.max(c.lengthSec, h.endTime));
        return c;
    }

    private static void parseInote(String body, Chart c) {
        double time = 0;
        int div = 1;
        String[] lines = body.split("\\r?\\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.equals("E")) break;
            if (line.startsWith("{")) {
                int close = line.indexOf('}');
                if (close > 1) {
                    try {
                        div = Integer.parseInt(line.substring(1, close));
                    } catch (NumberFormatException ignored) {
                    }
                }
                line = line.substring(close + 1);
            }
            String[] slots = line.split(",", -1);
            for (String slot : slots) {
                parseSlot(slot.trim(), time, c);
                time += 60.0 / c.bpm / div;
            }
        }
    }

    private static void parseSlot(String slot, double time, Chart c) {
        if (slot.isEmpty() || slot.equals("_")) return;
        for (String token : slot.split("/")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            if (t.equals("E")) continue;
            if (t.startsWith(">") || t.startsWith("<")) continue;   // 長滑動
            if (t.startsWith("C") || t.startsWith("Cf") || t.startsWith("Ch")) continue; // 滑條尾
            if (t.startsWith("E1") || t.startsWith("E8") || t.startsWith("A2f")
                    || t.startsWith("E3hf") || t.startsWith("E2hf")) continue; // 觸碰區
            // 綠鍵 B1-B8
            if (t.matches("B\\d.*")) {
                int k = Integer.parseInt(t.replaceAll("\\D", "").substring(0, 1));
                c.taps.add(new TapNote(time, k, false));
                continue;
            }
            // 觸碰 pp/qq（面板 1-8）
            if (t.matches("p\\d|q\\d")) {
                int k = Integer.parseInt(t.substring(1, 2));
                c.taps.add(new TapNote(time, k, false));
                continue;
            }
            // 長條 1h[1:2] / 1hx[1:2]
            Matcher hm = Pattern.compile("^(\\d)hx?\\[(\\d):(\\d)]").matcher(t);
            if (hm.find()) {
                int k = Integer.parseInt(hm.group(1));
                c.taps.add(new TapNote(time, k, false));
                c.holds.add(new HoldNote(time, k, time + 60.0 / c.bpm));
                continue;
            }
            // 滑條 8x-4[4:3] / 1-5[4:3]：開始鍵當 tap
            Matcher sm = Pattern.compile("^(\\d)[x\\-](\\d)").matcher(t);
            if (sm.find()) {
                int k = Integer.parseInt(sm.group(1));
                c.taps.add(new TapNote(time, k, false));
                continue;
            }
            // 純按鍵 1-8（可帶 x/b 後綴）
            Matcher km = Pattern.compile("^(\\d)([xb]?)$").matcher(t);
            if (km.find()) {
                int k = Integer.parseInt(km.group(1));
                boolean brk = km.group(2).equals("b");
                c.taps.add(new TapNote(time, k, brk));
                continue;
            }
            // 未知 token：嘗試抓開頭數字當按鍵
            Matcher any = Pattern.compile("^(\\d)").matcher(t);
            if (any.find()) {
                c.taps.add(new TapNote(time, Integer.parseInt(any.group(1)), false));
            }
        }
    }

    private static List<String[]> readHeaders(Path dir) {
        List<String[]> out = new ArrayList<>();
        Path f = dir.resolve("maidata.txt");
        if (!Files.isRegularFile(f)) return out;
        try {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            for (String line : lines) {
                Matcher m = INOTE.matcher(line);
                if (m.find()) out.add(new String[] { m.group(1), m.group(2) });
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String headerValue(Path dir, String key) {
        try {
            for (String line : Files.readAllLines(dir.resolve("maidata.txt"), StandardCharsets.UTF_8)) {
                if (line.startsWith(key)) return line.substring(key.length()).trim();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    // ---------------- 頁面 ----------------

    private static final class MaimaiPage extends ClickablePage {
        private final List<Path> charts = new ArrayList<>();
        private final Scroller scroller = new Scroller();
        private Chart current;
        private boolean playing;
        private final AudioEngine.Player player = new AudioEngine.Player();
        private double songStartSec = -1;
        private double speed = 55;
        private final Set<Integer> keysDown = new HashSet<>();
        private long lastTick;
        private final int[] judged = new int[3];   // P/G/M
        private int combo;
        private int maxCombo;
        private String result = "";
        private boolean finished;

        // 編輯器
        private boolean editing;
        private int editBpm = 180;
        private final Set<Integer> editNotes = new HashSet<>();  // grid = row(鍵1-8)*cols + col(1/4拍)
        private int editLen = 128;
        private int editScroll;
        private boolean newDialog;
        private String newName = "";
        private boolean naming;

        private String toast = "";
        private long toastUntil;

        private void toast(String s) {
            toast = s;
            toastUntil = System.currentTimeMillis() + 2000;
        }

        private void reload() {
            charts.clear();
            Path dir = Paths.dir("files", "maimai");
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isDirectory)
                        .filter(p -> Files.isRegularFile(p.resolve("maidata.txt")))
                        .forEach(charts::add);
            } catch (IOException ignored) {
            }
            charts.sort(Comparator.comparing(p -> p.getFileName().toString()));
            scroller.reset();
        }

        private void exitAll() {
            playing = false;
            editing = false;
            player.stop();
            songStartSec = -1;
            keysDown.clear();
            judged[0] = judged[1] = judged[2] = 0;
            combo = maxCombo = 0;
            finished = false;
            result = "";
            current = null;
            reload();
        }

        @Override
        public void onOpen() {
            reload();
        }

        @Override
        public void onClose() {
            exitAll();
        }

        private void startPlay(Path dir, int levelIdx, String inoteBody) {
            current = parseChart(dir, inoteBody, levelIdx);
            current.level = levelIdx;
            playing = true;
            editing = false;
            songStartSec = -1;
            keysDown.clear();
            judged[0] = judged[1] = judged[2] = 0;
            combo = maxCombo = 0;
            finished = false;
            result = "";
            // 音樂背景載入
            Path track = dir.resolve("track.mp3");
            if (Files.isRegularFile(track)) {
                Thread t = new Thread(() -> {
                    var s = AudioEngine.load(track);
                    if (s.isPresent()) {
                        net.minecraft.client.Minecraft.getInstance().execute(() -> player.play(s.get()));
                    }
                }, "mcphone-ultra-maimai-audio");
                t.setDaemon(true);
                t.start();
            }
        }

        @Override
        public void render(PhoneCanvas c) {
            int x = c.x(), y = c.y(), w = c.width(), h = c.height();
            var s = c.style();
            GuiGraphics g = c.graphics();
            Ui.fill(g, x, y, w, h, s.screenBackground());

            if (playing && current != null) {
                renderPlay(c, s, g);
                return;
            }
            if (editing && current != null) {
                renderEditor(c, s, g);
                return;
            }
            renderList(c, s, g);
        }

        // ---------------- 譜面列表 ----------------

        private void renderList(PhoneCanvas c, PhoneStyle s, GuiGraphics g) {
            int x = c.x(), y = c.y(), w = c.width(), h = c.height();
            Ui.textClipped(c, "🎵 maimai", x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            if (clickOn(x + 1, y + 13, 40, 12)) reload();
            Ui.button(c, x + 1, y + 13, 40, 12, true, c.hovered(x + 1, y + 13, 40, 12));
            Ui.buttonLabel(c, x + 1, y + 13, 40, 12, "刷新", true);
            if (clickOn(x + 43, y + 13, 40, 12)) newDialog = true;
            Ui.button(c, x + 43, y + 13, 40, 12, true, c.hovered(x + 43, y + 13, 40, 12));
            Ui.buttonLabel(c, x + 43, y + 13, 40, 12, "自製譜", true);
            if (clickOn(x + 85, y + 13, 34, 12)) {
                com.mcphoneultra.client.util.Http.openExternal("https://maimai.sega.jp/");
                toast("已用系統瀏覽器開 maimai 官網");
            }
            Ui.button(c, x + 85, y + 13, 34, 12, true, c.hovered(x + 85, y + 13, 34, 12));
            Ui.buttonLabel(c, x + 85, y + 13, 34, 12, "官網", true);

            Ui.textClipped(c, "譜面在 files/maimai/<譜名>/maidata.txt",
                    x + 3, y + 27, s.subtleColor(), x, y, w, 12);

            int listY = y + 42, listH = h - 42 - 16, rowH = 13;
            scroller.clamp(charts.size() * rowH, listH);
            int off = (int) scroller.offset();
            for (int i = 0; i < charts.size(); i++) {
                Path p = charts.get(i);
                int ry = listY + i * rowH - off;
                if (ry + rowH < listY || ry > listY + listH) continue;
                boolean hover = c.hovered(x, ry, w - 2, rowH);
                if (hover) Ui.fill(g, x, ry, w - 2, rowH, s.pressedOverlay());
                String title = headerValue(p, "&title=");
                if (title.isEmpty()) title = p.getFileName().toString();
                Ui.textClipped(c, "🎵 " + title, x + 3, ry, s.bodyColor(), x, ry, w - 40, rowH);
                if (clickOn(x + w - 38, ry, 36, rowH)) openChart(p);
                Ui.buttonLabel(c, x + w - 38, ry, 36, rowH, "進", true);
                Ui.hline(g, x + 2, x + w - 2, ry + rowH - 1, s.buttonDisabledColor());
            }
            Ui.scrollbar(c, x + w - 3, listY, listH, charts.size() * rowH, off);

            if (newDialog) dialogNewChart(c, s, g);

            if (System.currentTimeMillis() < toastUntil && !toast.isEmpty()) {
                Ui.drawCentered(c, toast, x, y + h - 14, w, 12, s.titleColor());
            }
        }

        /** 譜面詳情：選難度遊玩或編輯 */
        private void openChart(Path dir) {
            current = new Chart();
            current.name = dir.getFileName().toString();
            current.title = headerValue(dir, "&title=");
            current.artist = headerValue(dir, "&artist=");
            try {
                current.bpm = Double.parseDouble(headerValue(dir, "&wholebpm=").replaceAll("\\D", ""));
            } catch (NumberFormatException ignored) {
            }
            current.level = 0;
            // 詳情頁：列出難度
            List<String[]> inotes = readHeaders(dir);
            chartInotes = inotes;
            chartDir = dir;
            editing = true;   // 進入詳情/編輯頁
            editMode = false;
        }

        private Path chartDir;
        private List<String[]> chartInotes;
        private boolean editMode;

        // ---------------- 詳情/編輯入口頁 ----------------

        private void renderEditor(PhoneCanvas c, PhoneStyle s, GuiGraphics g) {
            int x = c.x(), y = c.y(), w = c.width(), h = c.height();
            if (editMode) {
                renderTimeline(c, s, g);
                return;
            }
            Ui.textClipped(c, "🎵 " + current.title, x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());
            Ui.textClipped(c, current.artist, x + 3, y + 13, s.subtleColor(), x, y, w, 12);
            Ui.textClipped(c, "BPM " + (int) current.bpm + " · " + chartDir.getFileName(),
                    x + 3, y + 25, s.subtleColor(), x, y, w, 12);

            int by = y + 40;
            Ui.text(c, "難度（&inote_X）", x + 3, by, s.subtleColor());
            by += 12;
            if (chartInotes != null) {
                for (String[] inote : chartInotes) {
                    String lv = "Lv?" ;
                    try {
                        lv = "Lv" + headerValue(chartDir, "&lv_" + inote[0] + "=");
                    } catch (Exception ignored) {
                    }
                    if (clickOn(x + 3, by, w - 6, 13)) startPlay(chartDir, Integer.parseInt(inote[0]), inote[1]);
                    if (c.hovered(x + 3, by, w - 6, 13)) Ui.fill(g, x + 3, by, w - 6, 13, s.pressedOverlay());
                    Ui.textClipped(c, "▶ " + lv + " 譜面" + inote[0], x + 5, by + 1, s.accentColor(), x, by, w, 13);
                    by += 14;
                }
            } else {
                Ui.text(c, "（沒有譜面資料）", x + 3, by, s.subtleColor());
            }

            by += 8;
            if (clickOn(x + 3, by, 54, 13)) {
                editMode = true;
                loadIntoEditor();
            }
            Ui.button(c, x + 3, by, 54, 13, true, c.hovered(x + 3, by, 54, 13));
            Ui.buttonLabel(c, x + 3, by, 54, 13, "✏ 編輯譜面", true);
            if (clickOn(x + 60, by, 54, 13)) exitAll();
            Ui.button(c, x + 60, by, 54, 13, true, c.hovered(x + 60, by, 54, 13));
            Ui.buttonLabel(c, x + 60, by, 54, 13, "← 返回", true);

            // 音樂檔提示
            by += 20;
            Ui.textClipped(c, "音樂：同資料夾 track.mp3（可用音樂編輯 App 處理）",
                    x + 3, by, s.subtleColor(), x, by, w, 20);
        }

        // ---------------- 遊玩 ----------------

        private static final int[] KEY_X = { 0, 1, 2, 3, 0, 1, 2, 3 };  // key 1..8 的欄
        private static final int[] KEY_Y = { 0, 0, 0, 0, 1, 1, 1, 1 };   // 列

        private void renderPlay(PhoneCanvas c, PhoneStyle s, GuiGraphics g) {
            int x = c.x(), y = c.y(), w = c.width(), h = c.height();
            Ui.fill(g, x, y, w, h, 0xFF10141C);

            double now = songSec();
            if (songStartSec < 0) songStartSec = now;
            double songTime = songStartSec < 0 ? 0 : now - songStartSec;

            // 標題
            Ui.textClipped(c, current.title + "  Lv" + current.level, x + 2, y + 2,
                    0xFFFFFFFF, x, y, w, 10);
            Ui.textClipped(c, String.format("P%d G%d M%d  連擊 %d", judged[0], judged[1], judged[2], combo),
                    x + 2, y + 11, 0xFFB0E0FF, x, y, w, 10);

            // 8 鍵面板（2 排 4 欄）
            int px = x + 2, py = y + 22;
            int cw2 = (w - 6) / 4, ch2 = (h - 26) / 2 - 2;
            int judgeY = py + ch2;   // 判定線（上排底部）

            // 畫面板
            for (int k = 1; k <= 8; k++) {
                int bx = px + KEY_X[k - 1] * (cw2 + 1), by2 = py + KEY_Y[k - 1] * (ch2 + 1);
                if (clickOn(bx, by2, cw2, ch2)) {
                    keysDown.add(k);
                    hitKey(k);
                }
                Ui.fill(g, bx, by2, cw2, ch2, 0xFF1E2632);
                boolean down = keysDown.contains(k);
                if (down) Ui.fill(g, bx, by2, cw2, ch2, 0xFF3A4A5C);
                Ui.border(c, bx, by2, cw2, ch2, 0xFF303A48);
                Ui.textClipped(c, String.valueOf(k), bx + cw2 / 2 - 3, by2 + 2, 0xFF607080,
                        bx, by2, cw2, ch2);
            }
            Ui.hline(g, x + 2, x + w - 2, judgeY, 0xFF88CCFF);

            // 音符下落（只畫上排區域內）
            for (TapNote t : current.taps) {
                if (t.time < songTime - 0.2) continue;
                double dt = t.time - songTime;
                if (dt * speed > judgeY - py) continue;
                int bx = px + KEY_X[t.key - 1] * (cw2 + 1);
                int by2 = judgeY - (int) (dt * speed);
                int size = 6;
                Ui.fill(g, bx + cw2 / 2 - size / 2, by2 - size / 2, size, size,
                        t.isBreak ? 0xFFFFAA00 : 0xFF55CCFF);
            }
            for (HoldNote h2 : current.holds) {
                if (h2.time < songTime - 0.2 || songTime > h2.endTime + 0.2) continue;
                double dt = h2.time - songTime;
                if (dt * speed > judgeY - py) continue;
                int bx = px + KEY_X[h2.key - 1] * (cw2 + 1);
                int by2 = judgeY - (int) (dt * speed);
                Ui.fill(g, bx + cw2 / 2 - 3, by2 - 3, 6, 6, 0xFF88FF88);
            }

            // 判定
            for (TapNote t : current.taps) {
                if (t.time < 0) continue;
                if (t.time - songTime > 0.2) break;
                if (songTime - t.time > 0.2) {
                    judged[2]++;
                    combo = 0;
                    t.time = -999;   // 標記已處理
                }
            }

            // hold 結束自動 ok（開始時已按下）
            for (HoldNote h2 : current.holds) {
                if (h2.judgedStart && !h2.judgedOk && songTime >= h2.endTime) {
                    h2.judgedOk = true;
                    combo++;
                    maxCombo = Math.max(maxCombo, combo);
                }
                if (!h2.judgedStart && songTime - h2.time > 0.2) {
                    judged[2]++;
                    combo = 0;
                    h2.judgedStart = true;
                    h2.judgedOk = true;
                }
            }

            // 結束
            double end = Math.max(current.lengthSec + 1.5, 10);
            if (!finished && songTime > end) {
                finished = true;
                result = String.format("成績  P%d G%d M%d  最大連擊 %d",
                        judged[0], judged[1], judged[2], maxCombo);
            }
            if (finished) {
                Ui.drawCentered(c, result, x, y + h / 2 - 20, w, 12, 0xFFFFFFFF);
                if (clickOn(x + w / 2 - 30, y + h / 2 - 4, 60, 13)) exitAll();
                Ui.button(c, x + w / 2 - 30, y + h / 2 - 4, 60, 13, true,
                        c.hovered(x + w / 2 - 30, y + h / 2 - 4, 60, 13));
                Ui.buttonLabel(c, x + w / 2 - 30, y + h / 2 - 4, 60, 13, "← 返回", true);
            } else if (clickOn(x + w - 30, y + 2, 28, 10)) {
                exitAll();
            }
            Ui.buttonLabel(c, x + w - 30, y + 2, 28, 10, "退出", true);

            Ui.textClipped(c, "按鍵 1-8 或點面板  ·  速度可調", x + 2, y + h - 11,
                    0xFF607080, x, y, w, 10);
        }

        private double songSec() {
            return System.nanoTime() / 1e9;
        }

        private void hitKey(int k) {
            if (current == null || !playing) return;
            double songTime = songStartSec < 0 ? 0 : songSec() - songStartSec;
            TapNote best = null;
            double bestD = 0.16;
            for (TapNote t : current.taps) {
                if (t.time < 0) continue;
                double d = Math.abs(t.time - songTime);
                if (t.key == k && d < bestD) {
                    bestD = d;
                    best = t;
                }
            }
            if (best != null) {
                if (bestD <= 0.05) judged[0]++;
                else judged[1]++;
                combo++;
                maxCombo = Math.max(maxCombo, combo);
                best.time = -999;
            }
            // hold 開始
            HoldNote hb = null;
            for (HoldNote h2 : current.holds) {
                if (!h2.judgedStart && h2.key == k && Math.abs(h2.time - songTime) < 0.16) {
                    hb = h2;
                }
            }
            if (hb != null) {
                hb.judgedStart = true;
                combo++;
                maxCombo = Math.max(maxCombo, combo);
            }
        }

        private void releaseKey(int k) {
            keysDown.remove(k);
        }

        // ---------------- 時間線編輯器 ----------------

        private void loadIntoEditor() {
            editMode = true;
            editNotes.clear();
            editBpm = (int) Math.round(current.bpm);
            // 若有 inote_2 解析填入
            if (chartInotes != null && !chartInotes.isEmpty()) {
                Chart parsed = parseChart(chartDir, chartInotes.get(0)[1], 2);
                editBpm = (int) Math.round(parsed.bpm);
                double perBeat = 60.0 / parsed.bpm;
                for (TapNote t : parsed.taps) {
                    int col = (int) Math.round(t.time / (perBeat / 4));
                    if (col >= 0 && col < 512) editNotes.add(rowCol(t.key, col));
                }
                for (HoldNote h2 : parsed.holds) {
                    int col = (int) Math.round(h2.time / (perBeat / 4));
                    if (col >= 0 && col < 512) editNotes.add(rowCol(h2.key, col));
                }
                editLen = Math.max(128, ((int) parsed.lengthSec * editBpm / 60 * 4 + 8) & ~7);
                if (editLen > 512) editLen = 512;
            }
            editScroll = 0;
        }

        private static int rowCol(int key, int col) {
            return (key - 1) * 512 + col;
        }

        private void renderTimeline(PhoneCanvas c, PhoneStyle s, GuiGraphics g) {
            int x = c.x(), y = c.y(), w = c.width(), h = c.height();
            Ui.textClipped(c, "✏ 編輯：" + current.title, x + 2, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            int by = y + 13;
            if (clickOn(x + 1, by, 30, 11)) editScroll = Math.max(0, editScroll - 16);
            Ui.button(c, x + 1, by, 30, 11, true, c.hovered(x + 1, by, 30, 11));
            Ui.buttonLabel(c, x + 1, by, 30, 11, "◀◀", true);
            if (clickOn(x + 33, by, 26, 11)) editScroll += 16;
            Ui.button(c, x + 33, by, 26, 11, true, c.hovered(x + 33, by, 26, 11));
            Ui.buttonLabel(c, x + 33, by, 26, 11, "▶▶", true);
            if (clickOn(x + 61, by, 30, 11)) saveEditor();
            Ui.button(c, x + 61, by, 30, 11, true, c.hovered(x + 61, by, 30, 11));
            Ui.buttonLabel(c, x + 61, by, 30, 11, "儲存", true);
            if (clickOn(x + 93, by, 26, 11)) {
                editMode = false;
            }
            Ui.button(c, x + 93, by, 26, 11, true, c.hovered(x + 93, by, 26, 11));
            Ui.buttonLabel(c, x + 93, by, 26, 11, "返回", true);

            Ui.textClipped(c, "BPM " + editBpm + "  點格子放/刪音符  鍵1-8", x + 2, y + 26,
                    s.subtleColor(), x, y, w, 12);

            // 8 鍵時間線：行 = 鍵 1..8，列 = 1/4 拍
            int gridX = x + 2, gridY = y + 38;
            int rowH = 12, colsVisible = w - 4 - 14;
            int colW = 7;
            int maxCol = Math.min(editLen, editScroll + colsVisible / colW + 1);
            for (int k = 1; k <= 8; k++) {
                int ry = gridY + (k - 1) * rowH;
                Ui.textClipped(c, String.valueOf(k), gridX, ry, s.subtleColor(), gridX, ry, 12, rowH);
                for (int col = editScroll; col < maxCol; col++) {
                    int cx2 = gridX + 14 + (col - editScroll) * colW;
                    boolean has = editNotes.contains(rowCol(k, col));
                    int bg = has ? 0xFF55CCFF : ((col % 4 == 0) ? 0xFF1A2330 : 0xFF141B26);
                    Ui.fill(g, cx2, ry, colW, rowH - 1, bg);
                    if (clickOn(cx2, ry, colW, rowH - 1)) {
                        int key = rowCol(k, col);
                        if (!editNotes.remove(key)) editNotes.add(key);
                    }
                }
            }
            Ui.hline(g, gridX, x + w - 2, gridY + 8 * rowH, s.buttonDisabledColor());

            Ui.textClipped(c, "滾輪捲動時間軸", x + 2, y + h - 12, s.subtleColor(), x, y, w, 12);
        }

        private void saveEditor() {
            try {
                Path dir = chartDir;
                double perBeat = 60.0 / editBpm;
                StringBuilder sb = new StringBuilder();
                sb.append("&title=").append(current.title.isEmpty() ? current.name : current.title).append("\n");
                sb.append("&artist=").append(current.artist).append("\n");
                sb.append("&wholebpm=").append(editBpm).append("\n");
                sb.append("&inote_2=(").append(editBpm).append("){4}\n");
                // 每 4 槽一行（槽=1/4 拍）
                int totalSlots = (editLen + 3) & ~3;
                for (int s0 = 0; s0 < totalSlots; s0 += 4) {
                    StringBuilder line = new StringBuilder();
                    for (int slot = s0; slot < s0 + 4; slot++) {
                        StringBuilder keys = new StringBuilder();
                        for (int k = 1; k <= 8; k++) {
                            if (editNotes.contains(rowCol(k, slot))) {
                                if (keys.length() > 0) keys.append("/");
                                keys.append(k);
                            }
                        }
                        line.append(keys).append(",");
                    }
                    sb.append(line).append("\n");
                }
                sb.append("E\n");
                Files.writeString(dir.resolve("maidata.txt"), sb.toString(), StandardCharsets.UTF_8);
                toast("已存 " + dir.getFileName());
            } catch (IOException e) {
                toast("儲存失敗");
            }
        }

        private void dialogNewChart(PhoneCanvas c, PhoneStyle s, GuiGraphics g) {
            int x = c.x(), y = c.y(), w = c.width(), h = c.height();
            int px = x + 6, py = y + 40;
            Ui.fill(g, px, py, w - 12, 66, 0xFF202830);
            Ui.border(c, px, py, w - 12, 66, s.accentColor());
            Ui.text(c, "新譜名（Enter 確定）", px + 3, py + 2, s.titleColor());
            Ui.fill(g, px + 3, py + 15, w - 18, 12, 0xFF000000);
            Ui.textClipped(c, newName + (System.currentTimeMillis() / 500 % 2 == 0 ? "▏" : ""),
                    px + 5, py + 16, s.bodyColor(), px, py, w - 12, 66);
            if (clickOn(px + 3, py + 31, 50, 11)) createChart();
            Ui.button(c, px + 3, py + 31, 50, 11, true, c.hovered(px + 3, py + 31, 50, 11));
            Ui.buttonLabel(c, px + 3, py + 31, 50, 11, "建立", true);
            if (clickOn(px + 57, py + 31, 50, 11)) {
                newDialog = false;
                newName = "";
            }
            Ui.button(c, px + 57, py + 31, 50, 11, true, c.hovered(px + 57, py + 31, 50, 11));
            Ui.buttonLabel(c, px + 57, py + 31, 50, 11, "取消", true);
        }

        private void createChart() {
            String name = Paths.safeName(newName);
            newDialog = false;
            newName = "";
            try {
                Path dir = Paths.dir("files", "maimai", name);
                Path f = dir.resolve("maidata.txt");
                if (Files.exists(f)) {
                    toast("同名譜面已存在");
                    return;
                }
                Files.writeString(f,
                        "&title=" + name + "\n&artist=\n&wholebpm=180\n"
                                + "&inote_2=(180){1}\nE\n", StandardCharsets.UTF_8);
                toast("已建立 " + name);
                reload();
            } catch (IOException e) {
                toast("建立失敗");
            }
        }

        // ---------------- 輸入 ----------------

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            super.mouseClicked(mouseX, mouseY, button);
            return true;
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            if (playing || newDialog) return true;
            if (editMode) {
                editScroll = Math.max(0, editScroll + (amount > 0 ? -2 : 2));
                return true;
            }
            scroller.onWheel(amount, charts.size() * 13, 130);
            return true;
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (naming || newDialog) {
                if (key == GLFW.GLFW_KEY_ENTER) {
                    if (newDialog) createChart();
                    naming = false;
                } else if (key == GLFW.GLFW_KEY_BACKSPACE && !newName.isEmpty()) {
                    newName = newName.substring(0, newName.length() - 1);
                }
                return true;
            }
            if (playing) {
                Integer k = gameKey(key);
                if (k != null) {
                    keysDown.add(k);
                    hitKey(k);
                    return true;
                }
                return true;
            }
            return false;
        }

        @Override
        public boolean keyReleased(int key, int scan, int mods) {
            if (playing) {
                Integer k = gameKey(key);
                if (k != null) {
                    releaseKey(k);
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean charTyped(char ch, int mods) {
            if (newDialog) {
                if (ch >= 32 && ch != 127) newName += ch;
                return true;
            }
            return false;
        }

        @Override
        public boolean capturesKeyboard() {
            return playing || newDialog;
        }

        private static Integer gameKey(int glfw) {
            if (glfw >= GLFW.GLFW_KEY_1 && glfw <= GLFW.GLFW_KEY_8) return glfw - GLFW.GLFW_KEY_1 + 1;
            if (glfw == GLFW.GLFW_KEY_Q) return 1;
            if (glfw == GLFW.GLFW_KEY_W) return 2;
            if (glfw == GLFW.GLFW_KEY_E) return 3;
            if (glfw == GLFW.GLFW_KEY_R) return 4;
            if (glfw == GLFW.GLFW_KEY_U) return 5;
            if (glfw == GLFW.GLFW_KEY_I) return 6;
            if (glfw == GLFW.GLFW_KEY_O) return 7;
            if (glfw == GLFW.GLFW_KEY_P) return 8;
            return null;
        }

        @Override
        public boolean onBack() {
            if (newDialog) {
                newDialog = false;
                newName = "";
                return true;
            }
            if (playing) {
                exitAll();
                return true;
            }
            if (editing && current != null) {
                if (editMode) {
                    editMode = false;
                    return true;
                }
                editing = false;
                reload();
                return true;
            }
            return false;
        }
    }
}
