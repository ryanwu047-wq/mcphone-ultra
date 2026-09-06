package com.mcphoneultra.client.app;

import com.mcphoneultra.client.io.Paths;
import com.mcphoneultra.client.ui.Ui;
import com.november.mcphone.api.client.ui.IPhonePage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * ⏰ 鬧鐘／定時：設定真實時間 HH:MM，到點提醒（手機關著也會響）。
 * 資料存 files/alarms.txt，每行：HH:MM\t標籤\t啟用
 */
public final class AlarmApp extends BaseApp {

    public AlarmApp() {
        super("alarm", false);
    }

    @Override
    protected IPhonePage createPage() {
        return new AlarmPage();
    }

    /** 客戶端 tick：檢查鬧鐘是否到點（由 MCphone 掛載，手機關著也會響） */
    public static void onClientTick() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        LocalTime cur = LocalTime.now();
        for (Alarm a : load()) {
            if (!a.enabled) continue;
            LocalTime t = a.time;
            if (t.getHour() != cur.getHour() || t.getMinute() != cur.getMinute()) continue;
            long now = System.currentTimeMillis();
            String key = t.toString() + "|" + a.label;
            if (Math.abs(now - lastFired.getOrDefault(key, 0L)) < 60000) continue;
            lastFired.put(key, now);
            mc.player.displayClientMessage(
                    Component.literal("⏰ 鬧鐘：「" + a.label + "」時間到！"), false);
            if (mc.level != null) {
                mc.level.playLocalSound(mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                        SoundEvents.BELL_RESONATE, SoundSource.MASTER, 1.0f, 1.0f, false);
            }
        }
    }

    // ---- 資料 ----

    public record Alarm(LocalTime time, String label, boolean enabled, long firedAt) {
    }

    private static final java.util.Map<String, Long> lastFired = new java.util.HashMap<>();

    private static Path file() {
        return Paths.file("alarms.txt");
    }

    static List<Alarm> load() {
        List<Alarm> out = new ArrayList<>();
        Path p = file();
        if (!Files.isRegularFile(p)) return out;
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", -1);
                if (f.length < 2) continue;
                try {
                    out.add(new Alarm(LocalTime.parse(f[0]), f[1],
                            f.length < 3 || f[2].equals("1"),
                            lastFired.getOrDefault(f[0] + "|" + f[1], 0L)));
                } catch (Exception ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    static void save(List<Alarm> list) {
        try {
            Files.createDirectories(file().getParent());
            StringBuilder sb = new StringBuilder();
            for (Alarm a : list) {
                sb.append(a.time.format(DateTimeFormatter.ofPattern("HH:mm")))
                        .append('\t').append(a.label)
                        .append('\t').append(a.enabled ? "1" : "0").append('\n');
            }
            Files.write(file(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    // ---- 頁面 ----

    private static final class AlarmPage extends ClickablePage {
        private List<Alarm> list = List.of();
        private String input = "";
        private String inputLabel = "";
        private boolean typingTime;
        private boolean typingLabel;
        private String toast = "";
        private long toastUntil;

        private void toast(String s) {
            toast = s;
            toastUntil = System.currentTimeMillis() + 2000;
        }

        private void reload() {
            list = AlarmApp.load();
        }

        @Override
        public void onOpen() {
            reload();
        }

        @Override
        public void render(com.november.mcphone.api.client.ui.PhoneCanvas c) {
            int x = c.x(), y = c.y(), w = c.width(), h = c.height();
            var s = c.style();
            GuiGraphics g = c.graphics();
            Ui.fill(g, x, y, w, h, s.screenBackground());
            Ui.textClipped(c, "⏰ 鬧鐘", x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            // 新增區
            Ui.text(c, "時間 HH:MM", x + 4, y + 14, s.subtleColor());
            if (clickOn(x + 4, y + 24, 52, 11)) typingTime = true;
            Ui.fill(g, x + 4, y + 24, 52, 11, 0xFF000000);
            Ui.textClipped(c, (input.isEmpty() ? "07:00" : input), x + 6, y + 25,
                    s.accentColor(), x, y + 24, 52, 11);
            Ui.text(c, "標籤", x + 62, y + 14, s.subtleColor());
            if (clickOn(x + 62, y + 24, 52, 11)) typingLabel = true;
            Ui.fill(g, x + 62, y + 24, 52, 11, 0xFF000000);
            Ui.textClipped(c, (inputLabel.isEmpty() ? "起床" : inputLabel), x + 64, y + 25,
                    s.accentColor(), x, y + 24, 52, 11);
            if (clickOn(x + 118, y + 24, 30, 11)) {
                try {
                    LocalTime t = LocalTime.parse(input.isEmpty() ? "07:00" : input);
                    String label = inputLabel.isEmpty() ? "鬧鐘" : inputLabel;
                    list = new ArrayList<>(list);
                    list.add(0, new Alarm(t, label, true, 0L));
                    AlarmApp.save(list);
                    input = "";
                    inputLabel = "";
                    toast("已設定 " + t);
                } catch (Exception e) {
                    toast("時間格式錯（HH:MM）");
                }
            }
            Ui.button(c, x + 118, y + 24, 30, 11, true, c.hovered(x + 118, y + 24, 30, 11));
            Ui.buttonLabel(c, x + 118, y + 24, 30, 11, "添加", true);

            // 列表
            int listY = y + 40, listH = h - 40 - 16, rowH = 13;
            for (int i = 0; i < list.size(); i++) {
                Alarm a = list.get(i);
                int ry = listY + i * rowH;
                if (ry + rowH > listY + listH) break;
                Ui.fill(g, x + 2, ry, w - 4, rowH - 1,
                        a.enabled ? 0xFF1E2A3A : 0xFF161B22);
                Ui.hline(g, x + 2, x + w - 2, ry + rowH - 1, s.buttonDisabledColor());
                Ui.textClipped(c, a.time.format(DateTimeFormatter.ofPattern("HH:mm"))
                                + "  " + a.label + (a.enabled ? "" : "（關）"),
                        x + 5, ry + 2, a.enabled ? s.titleColor() : s.subtleColor(), x, ry, w - 60, rowH);
                if (clickOn(x + w - 56, ry, 26, rowH - 1)) {
                    list.set(i, new Alarm(a.time, a.label, !a.enabled, 0L));
                    AlarmApp.save(list);
                }
                Ui.button(c, x + w - 56, ry, 26, rowH - 1, true, c.hovered(x + w - 56, ry, 26, rowH - 1));
                Ui.buttonLabel(c, x + w - 56, ry, 26, rowH - 1, a.enabled ? "關" : "開", true);
                if (clickOn(x + w - 28, ry, 26, rowH - 1)) {
                    list.remove(i);
                    AlarmApp.save(list);
                    i--;
                }
                Ui.button(c, x + w - 28, ry, 26, rowH - 1, true, c.hovered(x + w - 28, ry, 26, rowH - 1));
                Ui.buttonLabel(c, x + w - 28, ry, 26, rowH - 1, "刪", true);
            }
            Ui.drawCentered(c, "按真實時鐘提醒，手機關著也會響", x, y + h - 13, w, 12, s.subtleColor());

            if (System.currentTimeMillis() < toastUntil && !toast.isEmpty()) {
                Ui.drawCentered(c, toast, x, y + h - 14, w, 12, s.titleColor());
            }
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (typingTime) {
                if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE && !input.isEmpty()) {
                    input = input.substring(0, input.length() - 1);
                }
                return true;
            }
            if (typingLabel) {
                if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE && !inputLabel.isEmpty()) {
                    inputLabel = inputLabel.substring(0, inputLabel.length() - 1);
                }
                return true;
            }
            return false;
        }

        @Override
        public boolean charTyped(char ch, int mods) {
            if (typingTime) {
                if (Character.isDigit(ch) && input.length() < 5) input += ch;
                return true;
            }
            if (typingLabel) {
                if (ch >= 32 && ch != 127 && inputLabel.length() < 12) inputLabel += ch;
                return true;
            }
            return false;
        }

        @Override
        public boolean onBack() {
            if (typingTime || typingLabel) {
                typingTime = false;
                typingLabel = false;
                return true;
            }
            return false;
        }

        @Override
        public boolean capturesKeyboard() {
            return typingTime || typingLabel;
        }
    }
}
