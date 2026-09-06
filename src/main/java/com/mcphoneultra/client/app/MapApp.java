package com.mcphoneultra.client.app;

import com.mcphoneultra.client.io.Paths;
import com.mcphoneultra.client.ui.Ui;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import com.november.mcphone.api.client.ui.PhoneStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 🗺 探索地圖：手機裡檢視走過的地形（地表色塊），支援縮放、設家、
 * 輸入座標導航（A* 找最平的路徑並畫線）。
 */
public final class MapApp extends BaseApp {

    public MapApp() {
        super("map", true);
    }

    @Override
    protected IPhonePage createPage() {
        return new MapPage();
    }

    private static final class MapPage extends ClickablePage {
        private int scale = 2; // px / chunk
        private String toast = "";
        private long toastUntil;
        private String inputX = "", inputZ = "";
        private boolean typingX, typingZ, navMode;
        private ExploreMap.PathResult path = null;
        private double homeX = Double.NaN, homeZ = Double.NaN;

        private void toast(String s) {
            toast = s;
            toastUntil = System.currentTimeMillis() + 2500;
        }

        @Override
        public void onOpen() {
            ExploreMap.load(ExploreMap.currentDim());
            loadHome();
        }

        @Override
        public void onClose() {
            ExploreMap.save(ExploreMap.currentDim());
        }

        private void loadHome() {
            homeX = Double.NaN;
            homeZ = Double.NaN;
            try {
                Path p = Paths.file("map", "home.txt");
                if (Files.isRegularFile(p)) {
                    String[] f = Files.readString(p).trim().split(",");
                    if (f.length >= 3 && f[0].equals(ExploreMap.currentDim())) {
                        homeX = Double.parseDouble(f[1]);
                        homeZ = Double.parseDouble(f[2]);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        private void saveHome() {
            try {
                Path p = Paths.file("map", "home.txt");
                Files.createDirectories(p.getParent());
                Files.write(p, (ExploreMap.currentDim() + "," + (int) homeX + "," + (int) homeZ)
                        .getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        }

        @Override
        public void render(PhoneCanvas c) {
            int x = c.x(), y = c.y(), w = c.width(), h = c.height();
            var s = c.style();
            GuiGraphics g = c.graphics();
            Ui.fill(g, x, y, w, h, s.screenBackground());

            var mc = Minecraft.getInstance();
            if (mc.player == null) return;
            String dim = ExploreMap.currentDim();
            Map<Long, ExploreMap.Cell> cells = ExploreMap.cells(dim);
            int px = (int) Math.floor(mc.player.getX() / 16);
            int pz = (int) Math.floor(mc.player.getZ() / 16);

            // 標題與按鈕
            Ui.textClipped(c, "🗺 探索地圖" + (navMode ? "（導航）" : ""), x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            if (clickOn(x + w - 12, y + 1, 10, 10)) scale = Math.min(6, scale + 1);
            Ui.button(c, x + w - 12, y + 1, 10, 10, true, c.hovered(x + w - 12, y + 1, 10, 10));
            Ui.buttonLabel(c, x + w - 12, y + 1, 10, 10, "+", true);
            if (clickOn(x + w - 23, y + 1, 10, 10)) scale = Math.max(1, scale - 1);
            Ui.button(c, x + w - 23, y + 1, 10, 10, true, c.hovered(x + w - 23, y + 1, 10, 10));
            Ui.buttonLabel(c, x + w - 23, y + 1, 10, 10, "-", true);

            // 第二排：設家 / 導航 / 清除路線
            int by = y + 13;
            if (clickOn(x + 2, by, 26, 11)) {
                homeX = mc.player.getX();
                homeZ = mc.player.getZ();
                saveHome();
                toast("已設定家");
            }
            Ui.button(c, x + 2, by, 26, 11, true, c.hovered(x + 2, by, 26, 11));
            Ui.buttonLabel(c, x + 2, by, 26, 11, "🏠設家", true);

            if (clickOn(x + 30, by, 26, 11)) {
                navMode = !navMode;
                path = null;
                if (navMode) typingX = true;
            }
            Ui.button(c, x + 30, by, 26, 11, true, c.hovered(x + 30, by, 26, 11));
            Ui.buttonLabel(c, x + 30, by, 26, 11, navMode ? "✖退出" : "🧭導航", true);

            if (clickOn(x + 58, by, 26, 11)) path = null;
            Ui.button(c, x + 58, by, 26, 11, true, c.hovered(x + 58, by, 26, 11));
            Ui.buttonLabel(c, x + 58, by, 26, 11, "🚫清路", true);

            if (clickOn(x + 86, by, 32, 11)) {
                // 直接導航回家
                if (!Double.isNaN(homeX)) {
                    path = ExploreMap.findPath(dim, mc.player.getX(), mc.player.getZ(), homeX, homeZ);
                    if (path.found) toast("回家路徑 " + path.steps + " 格");
                    else toast("已探索區域到不了家");
                } else {
                    toast("先設家");
                }
            }
            Ui.button(c, x + 86, by, 32, 11, true, c.hovered(x + 86, by, 32, 11));
            Ui.buttonLabel(c, x + 86, by, 32, 11, "↩回家", true);

            int mapY = by + 14;
            int mapH = h - mapY - 26;

            // 導航輸入
            if (navMode) {
                Ui.text(c, "目標 X", x + 2, mapY + 1, s.subtleColor());
                if (clickOn(x + 2, mapY + 10, 36, 11)) typingX = true;
                Ui.fill(g, x + 2, mapY + 10, x + 38, mapY + 21, 0xFF000000);
                Ui.textClipped(c, inputX.isEmpty() ? "0" : inputX, x + 4, mapY + 11, s.accentColor(), x, mapY + 10, 36, 11);
                Ui.text(c, "Z", x + 42, mapY + 1, s.subtleColor());
                if (clickOn(x + 42, mapY + 10, 36, 11)) typingZ = true;
                Ui.fill(g, x + 42, mapY + 10, x + 78, mapY + 21, 0xFF000000);
                Ui.textClipped(c, inputZ.isEmpty() ? "0" : inputZ, x + 44, mapY + 11, s.accentColor(), x, mapY + 10, 36, 11);
                if (clickOn(x + 82, mapY + 10, 36, 11)) {
                    try {
                        double tx = Double.parseDouble(inputX), tz = Double.parseDouble(inputZ);
                        path = ExploreMap.findPath(dim, mc.player.getX(), mc.player.getZ(), tx, tz);
                        if (path.found) toast("路徑 " + path.steps + " 格（高度差最小）");
                        else toast("已探索區域到不了目標");
                        navMode = false;
                    } catch (Exception e) {
                        toast("輸入整數座標");
                    }
                }
                Ui.button(c, x + 82, mapY + 10, 36, 11, true, c.hovered(x + 82, mapY + 10, 36, 11));
                Ui.buttonLabel(c, x + 82, mapY + 10, 36, 11, "出發", true);
                mapY += 24;
                mapH -= 24;
            }

            // 地圖
            int drawnW = w - 4, drawnH = mapH - 2;
            int halfW = drawnW / (2 * scale), halfH = drawnH / (2 * scale);
            int mapX0 = x + 2, mapY0 = mapY + 2;
            int pcx = mapX0 + drawnW / 2, pcy = mapY0 + drawnH / 2;

            for (int dxc = -halfW; dxc <= halfW; dxc++) {
                for (int dzc = -halfH; dzc <= halfH; dzc++) {
                    ExploreMap.Cell cell = cells.get(((long) (px + dxc) << 32) | ((pz + dzc) & 0xFFFFFFFFL));
                    int sx = pcx + dxc * scale, sy = pcy + dzc * scale;
                    if (cell != null) {
                        Ui.fill(g, sx, sy, sx + scale, sy + scale, cell.color);
                    } else {
                        Ui.fill(g, sx, sy, sx + scale, sy + scale, 0xFF141414);
                    }
                }
            }

            // 路徑（黃線）
            if (path != null && path.found && path.chunks.size() > 1) {
                for (int i = 1; i < path.chunks.size(); i++) {
                    long[] a = path.chunks.get(i - 1), b = path.chunks.get(i);
                    int ax = pcx + (int) (a[0] - px) * scale + scale / 2;
                    int ay = pcy + (int) (a[1] - pz) * scale + scale / 2;
                    int bx = pcx + (int) (b[0] - px) * scale + scale / 2;
                    int by2 = pcy + (int) (b[1] - pz) * scale + scale / 2;
                    line(g, ax, ay, bx, by2, 0xFFFFD700);                }
            }

            // 家（紅點）
            if (!Double.isNaN(homeX)) {
                int hx = pcx + (int) (Math.floor(homeX / 16) - px) * scale + scale / 2;
                int hz = pcy + (int) (Math.floor(homeZ / 16) - pz) * scale + scale / 2;
                Ui.fill(g, hx - 1, hz - 1, hx + 2, hz + 2, 0xFFFF4040);
            }

            // 玩家（白點）
            Ui.fill(g, pcx - 1, pcy - 1, pcx + 2, pcy + 2, 0xFFFFFFFF);

            // 底部資訊
            String info = "X " + (int) mc.player.getX() + " Z " + (int) mc.player.getZ();
            if (path != null && path.found) {
                info += "  路徑 " + path.steps + "格";
                long[] next = path.chunks.size() > 1 ? path.chunks.get(1) : path.chunks.get(0);
                int dxn = (int) next[0] - px, dzn = (int) next[1] - pz;
                info += "  向" + (Math.abs(dxn) >= Math.abs(dzn)
                        ? (dxn >= 0 ? "東" : "西") + Math.abs(dxn) * 16 + "m"
                        : (dzn >= 0 ? "南" : "北") + Math.abs(dzn) * 16 + "m");
            }
            Ui.drawCentered(c, info, x, y + h - 13, w, 12, s.titleColor());

            if (System.currentTimeMillis() < toastUntil && !toast.isEmpty()) {
                Ui.drawCentered(c, toast, x, y + h - 14, w, 12, s.titleColor());
            }
        }

        /** Bresenham 直線（畫路徑用） */
        private static void line(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
            int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
            int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
            int err = dx + dy;
            while (true) {
                if (x0 >= 0 && y0 >= 0) Ui.fill(g, x0, y0, x0 + 1, y0 + 1, color);
                if (x0 == x1 && y0 == y1) break;
                int e2 = 2 * err;
                if (e2 >= dy) {
                    err += dy;
                    x0 += sx;
                }
                if (e2 <= dx) {
                    err += dx;
                    y0 += sy;
                }
            }
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (typingX) {
                if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE && !inputX.isEmpty()) {
                    inputX = inputX.substring(0, inputX.length() - 1);
                }
                return true;
            }
            if (typingZ) {
                if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE && !inputZ.isEmpty()) {
                    inputZ = inputZ.substring(0, inputZ.length() - 1);
                }
                return true;
            }
            return false;
        }

        @Override
        public boolean charTyped(char ch, int mods) {
            if (typingX) {
                if ((Character.isDigit(ch) || ch == '-') && inputX.length() < 8) inputX += ch;
                return true;
            }
            if (typingZ) {
                if ((Character.isDigit(ch) || ch == '-') && inputZ.length() < 8) inputZ += ch;
                return true;
            }
            return false;
        }

        @Override
        public boolean onBack() {
            if (typingX || typingZ) {
                typingX = false;
                typingZ = false;
                return true;
            }
            return false;
        }

        @Override
        public boolean capturesKeyboard() {
            return typingX || typingZ;
        }
    }
}
