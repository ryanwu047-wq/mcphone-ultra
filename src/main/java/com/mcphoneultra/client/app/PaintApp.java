package com.mcphoneultra.client.app;

import com.mcphoneultra.client.io.Paths;
import com.mcphoneultra.client.ui.Scroller;
import com.mcphoneultra.client.ui.Ui;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import com.november.mcphone.api.client.ui.PhoneStyle;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 繪圖：可設畫布大小、縮放（滾輪）與移動（移動工具拖拽）、
 * 筆刷/直線/矩形/圓形（可實心）、文字、RGB 自訂色與 16 色調色板。
 * 存 PNG 到 files/paint/，可讀回繼續畫。
 */
public final class PaintApp extends BaseApp {

    public PaintApp() {
        super("paint", true);
    }

    @Override
    protected IPhonePage createPage() {
        return new PaintPage();
    }

    private static final class PaintPage extends ClickablePage {
        private static final int[] PALETTE = {
                0xFF000000, 0xFFFFFFFF, 0xFF9E9E9E, 0xFF795548,
                0xFFFF5252, 0xFFFF9800, 0xFFFFEB3B, 0xFF4CAF50,
                0xFF009688, 0xFF2196F3, 0xFF3F51B5, 0xFF9C27B0,
                0xFFE91E63, 0xFF00BCD4, 0xFF8BC34A, 0xFFFFC107,
        };
        private static final String[] TOOL_NAMES = { "筆", "線", "方", "圓", "文", "擦", "移" };

        private int cw = 96, ch = 64;
        private int[][] canvas = new int[ch][cw];     // ARGB，0=透明
        private int color = PALETTE[0];
        private int tool;                              // 0筆 1線 2矩形 3圓 4文字 5橡皮 6移動
        private boolean fill;                          // 形狀實心
        private int brush = 1;                         // 1..8
        private int zoom = 1;                          // 每畫素顯示 px
        private int viewX, viewY;                      // 視圖左上（畫布座標）

        private boolean drawing;
        private int lastX, lastY;
        private boolean shaping;
        private int shapeX0, shapeY0, shapeX1, shapeY1;
        private boolean panning;
        private int panLastX, panLastY;

        private final List<TextObj> texts = new ArrayList<>();
        private boolean typingText;
        private String textInput = "";
        private int textX, textY;

        private boolean newCanvasDialog;
        private int r, g, b;                           // 自訂 RGB
        private int gr;                                // 綠通道（避免與 GuiGraphics g 撞名）
        private boolean customColor;

        private String name = "畫作";
        private boolean naming;
        private final Scroller fileScroller = new Scroller();
        private boolean showFiles;
        private String toast = "";
        private long toastUntil;
        private int x, y, w, h;
        private int canvasTop, canvasW, canvasH;       // 畫布視窗（螢幕座標）

        private record TextObj(int tx, int ty, String text, int size, int color) {}

        private void toast(String s) {
            toast = s;
            toastUntil = System.currentTimeMillis() + 2000;
        }

        private void newCanvas(int w2, int h2) {
            cw = w2;
            ch = h2;
            canvas = new int[ch][cw];
            texts.clear();
            viewX = 0;
            viewY = 0;
            fitZoom();
            toast("新畫布 " + cw + "×" + ch);
        }

        private void fitZoom() {
            zoom = Math.max(1, Math.min(12,
                    Math.min(canvasW / Math.max(1, cw), canvasH / Math.max(1, ch))));
        }

        private void clearCanvas() {
            for (int yy = 0; yy < ch; yy++) for (int xx = 0; xx < cw; xx++) canvas[yy][xx] = 0;
            texts.clear();
        }

        private int curColor() {
            return eraseTool() ? 0 : (customColor ? 0xFF000000 | (r << 16) | (gr << 8) | b : color);
        }

        private boolean eraseTool() {
            return tool == 5;
        }

        private void paint(int cx, int cy) {
            int col = curColor();
            for (int dy = -brush; dy <= brush; dy++) {
                for (int dx = -brush; dx <= brush; dx++) {
                    if (dx * dx + dy * dy > (brush + 0.5) * (brush + 0.5)) continue;
                    int px = cx + dx, py = cy + dy;
                    if (px >= 0 && px < cw && py >= 0 && py < ch) canvas[py][px] = col;
                }
            }
        }

        private void lineCanvas(int x0, int y0, int x1, int y1) {
            int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
            int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
            int err = dx - dy;
            int guard = 0;
            while (guard++ < 5000) {
                paint(x0, y0);
                if (x0 == x1 && y0 == y1) break;
                int e2 = 2 * err;
                if (e2 > -dy) { err -= dy; x0 += sx; }
                if (e2 < dx) { err += dx; y0 += sy; }
            }
        }

        private void rectCanvas(int x0, int y0, int x1, int y1) {
            int col = curColor();
            int minX = Math.min(x0, x1), maxX = Math.max(x0, x1);
            int minY = Math.min(y0, y1), maxY = Math.max(y0, y1);
            if (fill) {
                for (int yy = minY; yy <= maxY; yy++)
                    for (int xx = minX; xx <= maxX; xx++)
                        if (xx >= 0 && xx < cw && yy >= 0 && yy < ch) canvas[yy][xx] = col;
            } else {
                lineCanvas(minX, minY, maxX, minY);
                lineCanvas(maxX, minY, maxX, maxY);
                lineCanvas(maxX, maxY, minX, maxY);
                lineCanvas(minX, maxY, minX, minY);
            }
        }

        private void circleCanvas(int x0, int y0, int x1, int y1) {
            int col = curColor();
            int cx2 = (x0 + x1) / 2, cy2 = (y0 + y1) / 2;
            int rx = Math.max(1, Math.abs(x1 - x0) / 2), ry = Math.max(1, Math.abs(y1 - y0) / 2);
            if (fill) {
                for (int yy = -ry; yy <= ry; yy++) {
                    int half = (int) (rx * Math.sqrt(1 - (double) (yy * yy) / (ry * ry)));
                    for (int xx = -half; xx <= half; xx++) {
                        int px = cx2 + xx, py = cy2 + yy;
                        if (px >= 0 && px < cw && py >= 0 && py < ch) canvas[py][px] = col;
                    }
                }
            } else {
                int steps = Math.max(24, (rx + ry) * 2);
                int px = cx2 + rx, py = cy2;
                for (int i = 1; i <= steps; i++) {
                    double t = 2 * Math.PI * i / steps;
                    int nx = cx2 + (int) Math.round(rx * Math.cos(t));
                    int ny = cy2 + (int) Math.round(ry * Math.sin(t));
                    lineCanvas(px, py, nx, ny);
                    px = nx;
                    py = ny;
                }
            }
        }

        private void commitShape() {
            switch (tool) {
                case 1 -> lineCanvas(shapeX0, shapeY0, shapeX1, shapeY1);
                case 2 -> rectCanvas(shapeX0, shapeY0, shapeX1, shapeY1);
                case 3 -> circleCanvas(shapeX0, shapeY0, shapeX1, shapeY1);
                default -> { }
            }
        }

        private void save() {
            try {
                Path dir = Paths.dir("paint");
                Path p = dir.resolve(Paths.safeName(name) + ".png");
                BufferedImage img = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
                for (int yy = 0; yy < ch; yy++) {
                    for (int xx = 0; xx < cw; xx++) img.setRGB(xx, yy, canvas[yy][xx]);
                }
                Graphics2D g2 = img.createGraphics();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                for (TextObj t : texts) {
                    g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(6, t.size())));
                    g2.setColor(new Color(t.color(), true));
                    g2.drawString(t.text(), t.tx(), t.ty() + g2.getFont().getSize());
                }
                g2.dispose();
                ImageIO.write(img, "png", p.toFile());
                toast("已存 " + p.getFileName());
            } catch (IOException e) {
                toast("儲存失敗");
            }
        }

        private void load(String n) {
            try {
                Path p = Paths.file("paint", n + ".png");
                NativeImage img = com.mcphoneultra.client.util.Images.readAny(p);
                if (img == null) { toast("讀取失敗"); return; }
                cw = img.getWidth();
                ch = img.getHeight();
                canvas = new int[ch][cw];
                texts.clear();
                for (int yy = 0; yy < ch; yy++) {
                    for (int xx = 0; xx < cw; xx++) {
                        int abgr = img.getPixelRGBA(xx, yy);
                        int a = (abgr >>> 24) & 0xFF, bl = (abgr >>> 16) & 0xFF;
                        int gr = (abgr >>> 8) & 0xFF, rd = abgr & 0xFF;
                        canvas[yy][xx] = (a << 24) | (rd << 16) | (gr << 8) | bl;
                    }
                }
                img.close();
                name = n;
                viewX = 0;
                viewY = 0;
                fitZoom();
                toast("已載入 " + n);
            } catch (Exception e) {
                toast("讀取失敗");
            }
        }

        private List<String> listFiles() {
            List<String> out = new ArrayList<>();
            Path dir = Paths.dir("paint");
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                        .map(p -> p.getFileName().toString().replace(".png", ""))
                        .forEach(out::add);
            } catch (IOException ignored) {
            }
            out.sort(Comparator.naturalOrder());
            return out;
        }

        @Override
        public void onOpen() {
            if (canvas == null || canvas.length != ch) newCanvas(96, 64);
        }

        // ================= 事件 =================

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            super.mouseClicked(mouseX, mouseY, button);
            if (button == 0 && Ui.hit(x, canvasTop, canvasW, canvasH, (int) mouseX, (int) mouseY)) {
                int cx = toCanvasX((int) mouseX), cy = toCanvasY((int) mouseY);
                if (tool == 4) {
                    typingText = true;
                    textInput = "";
                    textX = cx;
                    textY = cy;
                } else if (tool == 6) {
                    panning = true;
                    panLastX = (int) mouseX;
                    panLastY = (int) mouseY;
                } else if (tool == 1 || tool == 2 || tool == 3) {
                    shaping = true;
                    shapeX0 = shapeX1 = cx;
                    shapeY0 = shapeY1 = cy;
                } else {
                    drawing = true;
                    lastX = cx;
                    lastY = cy;
                    paint(cx, cy);
                }
                return true;
            }
            return true;
        }

        @Override
        public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
            if (button != 0) return false;
            if (panning) {
                viewX -= (int) (dx / zoom);
                viewY -= (int) (dy / zoom);
                clampView();
                return true;
            }
            if (!Ui.hit(x, canvasTop, canvasW, canvasH, (int) mx, (int) my)) return false;
            int cx = toCanvasX((int) mx), cy = toCanvasY((int) my);
            if (drawing) {
                lineCanvas(lastX, lastY, cx, cy);
                lastX = cx;
                lastY = cy;
                return true;
            }
            if (shaping) {
                shapeX1 = cx;
                shapeY1 = cy;
                return true;
            }
            return false;
        }

        @Override
        public boolean mouseReleased(double mx, double my, int button) {
            if (shaping) {
                commitShape();
                shaping = false;
                return true;
            }
            drawing = false;
            panning = false;
            return false;
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            if (typingText || naming || newCanvasDialog || showFiles) return true;
            if (amount == 0) return true;
            // 以滑鼠位置為中心縮放
            int oldZoom = zoom;
            zoom = Math.max(1, Math.min(12, zoom + (amount > 0 ? 1 : -1)));
            if (zoom != oldZoom && Ui.hit(x, canvasTop, canvasW, canvasH, (int) mx, (int) my)) {
                double fx = (mx - canvasX0()) / oldZoom + viewX;
                double fy = (my - canvasTop) / oldZoom + viewY;
                viewX = (int) Math.round(fx - (mx - canvasX0()) / zoom);
                viewY = (int) Math.round(fy - (my - canvasTop) / zoom);
                clampView();
            }
            return true;
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (typingText) {
                if (key == GLFW.GLFW_KEY_ENTER) {
                    if (!textInput.isEmpty()) {
                        texts.add(new TextObj(textX, textY, textInput,
                                Math.max(8, brush * 4), curColor()));
                    }
                    typingText = false;
                } else if (key == GLFW.GLFW_KEY_BACKSPACE && !textInput.isEmpty()) {
                    textInput = textInput.substring(0, textInput.length() - 1);
                }
                return true;
            }
            if (naming) {
                if (key == GLFW.GLFW_KEY_ENTER) {
                    naming = false;
                    if (name.trim().isEmpty()) name = "畫作";
                } else if (key == GLFW.GLFW_KEY_BACKSPACE && !name.isEmpty()) {
                    name = name.substring(0, name.length() - 1);
                }
                return true;
            }
            return false;
        }

        @Override
        public boolean charTyped(char ch, int mods) {
            if (typingText) {
                if (ch >= 32 && ch != 127) textInput += ch;
                return true;
            }
            if (naming) {
                if (ch >= 32 && ch != 127) name += ch;
                return true;
            }
            return false;
        }

        @Override
        public boolean capturesKeyboard() {
            return typingText || naming;
        }

        @Override
        public boolean onBack() {
            if (typingText) { typingText = false; return true; }
            if (naming) { naming = false; return true; }
            if (newCanvasDialog) { newCanvasDialog = false; return true; }
            if (showFiles) { showFiles = false; return true; }
            drawing = false;
            shaping = false;
            panning = false;
            return false;
        }

        // ================= 座標 =================

        private int canvasX0() { return x + 1; }

        private int toCanvasX(int sx) { return (sx - canvasX0()) / zoom + viewX; }

        private int toCanvasY(int sy) { return (sy - canvasTop) / zoom + viewY; }

        private void clampView() {
            int vw = canvasW / zoom + 1, vh = canvasH / zoom + 1;
            if (viewX < 0) viewX = 0;
            if (viewY < 0) viewY = 0;
            if (viewX > cw - vw) viewX = Math.max(0, cw - vw);
            if (viewY > ch - vh) viewY = Math.max(0, ch - vh);
        }

        // ================= 渲染 =================

        @Override
        public void render(PhoneCanvas c) {
            x = c.x();
            y = c.y();
            w = c.width();
            h = c.height();
            var s = c.style();
            GuiGraphics g = c.graphics();
            Ui.fill(g, x, y, w, h, s.screenBackground());

            if (showFiles) { renderFiles(c, s, g); return; }

            Ui.textClipped(c, "🎨繪圖 " + name, x + 2, y + 1, s.titleColor(), x, y, w, 10);
            Ui.hline(g, x, x + w, y + 10, s.buttonDisabledColor());

            int by = y + 11, bh = 10;
            row1(c, s, g, by, bh);
            row2(c, s, g, by + 10, bh);
            row3(c, s, g, by + 20, bh);

            // 色盤 16 色（一排）
            int palTop = by + 32;
            int sw = (w - 4) / 16;
            for (int i = 0; i < 16; i++) {
                int px = x + 2 + i * sw, py = palTop;
                Ui.fill(g, px, py, sw - 1, 8, PALETTE[i]);
                if (clickOn(px, py, sw - 1, 8)) {
                    color = PALETTE[i];
                    customColor = false;
                    r = (color >>> 16) & 0xFF;
                    gr = (color >>> 8) & 0xFF;
                    b = color & 0xFF;
                }
                if (color == PALETTE[i] && !customColor) Ui.border(c, px, py, sw - 1, 8, 0xFFFFFFFF);
            }

            // RGB 自訂（一行）
            int rgbTop = palTop + 10;
            Ui.textClipped(c, "RGB", x + 2, rgbTop + 1, s.subtleColor(), x, rgbTop, w, 10);
            int vx = x + 16;
            int[] vals = { r, gr, b };
            String[] labels = { "R", "G", "B" };
            for (int i = 0; i < 3; i++) {
                if (clickOn(vx, rgbTop, 9, 9)) vals[i] = (vals[i] + 16) % 256;
                Ui.button(c, vx, rgbTop, 9, 9, true, c.hovered(vx, rgbTop, 9, 9));
                Ui.buttonLabel(c, vx, rgbTop, 9, 9, "+", true);
                if (clickOn(vx + 10, rgbTop, 9, 9)) vals[i] = (vals[i] - 16 + 256) % 256;
                Ui.button(c, vx + 10, rgbTop, 9, 9, true, c.hovered(vx + 10, rgbTop, 9, 9));
                Ui.buttonLabel(c, vx + 10, rgbTop, 9, 9, "-", true);
                Ui.textClipped(c, labels[i] + String.format("%02X", vals[i]),
                        vx + 21, rgbTop + 1, s.bodyColor(), x, rgbTop, w, 10);
                vx += 36;
            }
            if (vals[0] != r || vals[1] != gr || vals[2] != b) {
                r = vals[0];
                gr = vals[1];
                b = vals[2];
                customColor = true;
            }
            Ui.fill(g, x + w - 20, rgbTop, 18, 9, curColor());
            Ui.border(c, x + w - 20, rgbTop, 18, 9, s.buttonDisabledColor());

            // 畫布
            canvasTop = rgbTop + 12;
            canvasW = w;
            canvasH = h - (canvasTop - y) - 1;
            int vw2 = cw * zoom, vh2 = ch * zoom;
            int bx = canvasX0(), by2 = canvasTop;
            g.enableScissor(bx, by2, bx + canvasW, by2 + canvasH);
            Ui.fill(g, bx, by2, canvasW, canvasH, 0xFF101418);
            int x0 = Math.max(0, viewX), y0 = Math.max(0, viewY);
            int x1 = Math.min(cw - 1, viewX + canvasW / zoom + 1), y1 = Math.min(ch - 1, viewY + canvasH / zoom + 1);
            for (int cy = y0; cy <= y1; cy++) {
                for (int cx = x0; cx <= x1; cx++) {
                    int col = canvas[cy][cx];
                    if (col != 0) {
                        Ui.fill(g, bx + (cx - viewX) * zoom, by2 + (cy - viewY) * zoom,
                                zoom, zoom, col);
                    }
                }
            }
            // 文字疊加
            for (TextObj t : texts) {
                int sxp = bx + (t.tx() - viewX) * zoom, syp = by2 + (t.ty() - viewY) * zoom;
                if (sxp + t.size() * zoom < bx || syp + zoom < by2
                        || sxp > bx + canvasW || syp > by2 + canvasH) continue;
                g.pose().pushPose();
                g.pose().translate(sxp, syp, 0);
                g.pose().scale(zoom, zoom, 1);
                g.drawString(c.font(), t.text(), 0, 0, t.color(), false);
                g.pose().popPose();
            }
            // 形狀預覽
            if (shaping) {
                int x0s = bx + (shapeX0 - viewX) * zoom, y0s = by2 + (shapeY0 - viewY) * zoom;
                int x1s = bx + (shapeX1 - viewX) * zoom, y1s = by2 + (shapeY1 - viewY) * zoom;
                Ui.border(c, Math.min(x0s, x1s), Math.min(y0s, y1s),
                        Math.abs(x1s - x0s), Math.abs(y1s - y0s), 0x99FFFFFF);
            }
            g.disableScissor();
            Ui.border(c, bx, by2, canvasW, canvasH, s.buttonDisabledColor());
            Ui.textClipped(c, zoom + "x" + (tool == 6 ? " 拖拽移動" : tool == 4 ? " 點畫布放字" : ""),
                    x + 2, by2 + canvasH - 9, s.subtleColor(), x, by2, canvasW, 10);

            // 滑鼠格線
            if (c.hovered(bx, by2, canvasW, canvasH) && !shaping && !panning && tool != 6) {
                int ccx = toCanvasX(c.mouseX()), ccy = toCanvasY(c.mouseY());
                if (ccx >= 0 && ccx < cw && ccy >= 0 && ccy < ch) {
                    Ui.border(c, bx + (ccx - viewX) * zoom, by2 + (ccy - viewY) * zoom,
                            zoom, zoom, 0xCCFFFFFF);
                }
            }

            if (System.currentTimeMillis() < toastUntil && !toast.isEmpty()) {
                Ui.drawCentered(c, toast, x, y + h - 10, w, 9, s.titleColor());
            }

            if (typingText) dialogText(c, s, g);
            else if (naming) dialogName(c, s, g);
            else if (newCanvasDialog) dialogNew(c, s, g);
        }

        private void row1(PhoneCanvas c, PhoneStyle s, GuiGraphics g, int by, int bh) {
            String[] labels = { "存檔", "讀檔", "清空", "命名", "新建" };
            for (int i = 0; i < 5; i++) {
                int bx = x + 1 + i * 24;
                boolean enabled = true;
                if (clickOn(bx, by, 23, bh)) {
                    switch (i) {
                        case 0 -> save();
                        case 1 -> showFiles = true;
                        case 2 -> clearCanvas();
                        case 3 -> naming = true;
                        case 4 -> newCanvasDialog = true;
                    }
                }
                Ui.button(c, bx, by, 23, bh, enabled, c.hovered(bx, by, 23, bh));
                Ui.buttonLabel(c, bx, by, 23, bh, labels[i], enabled);
            }
        }

        private void row2(PhoneCanvas c, PhoneStyle s, GuiGraphics g, int by, int bh) {
            for (int i = 0; i < 7; i++) {
                int bx = x + 1 + i * 17;
                if (clickOn(bx, by, 16, bh)) {
                    tool = i;
                    drawing = false;
                    shaping = false;
                    panning = false;
                }
                boolean active = tool == i;
                Ui.button(c, bx, by, 16, bh, true, active || c.hovered(bx, by, 16, bh));
                Ui.buttonLabel(c, bx, by, 16, bh, TOOL_NAMES[i], true);
                if (active) Ui.border(c, bx, by, 16, bh, s.accentColor());
            }
        }

        private void row3(PhoneCanvas c, PhoneStyle s, GuiGraphics g, int by, int bh) {
            if (clickOn(x + 1, by, 18, bh)) brush = Math.max(1, brush - 1);
            Ui.button(c, x + 1, by, 18, bh, true, c.hovered(x + 1, by, 18, bh));
            Ui.buttonLabel(c, x + 1, by, 18, bh, "-", true);
            if (clickOn(x + 20, by, 24, bh)) brush = brush == 1 ? 3 : (brush == 3 ? 5 : (brush == 5 ? 8 : 1));
            Ui.button(c, x + 20, by, 24, bh, true, c.hovered(x + 20, by, 24, bh));
            Ui.buttonLabel(c, x + 20, by, 24, bh, "B" + brush, true);
            if (clickOn(x + 45, by, 18, bh)) brush = Math.min(8, brush + 1);
            Ui.button(c, x + 45, by, 18, bh, true, c.hovered(x + 45, by, 18, bh));
            Ui.buttonLabel(c, x + 45, by, 18, bh, "+", true);
            if (clickOn(x + 65, by, 26, bh)) fill = !fill;
            Ui.button(c, x + 65, by, 26, bh, true, c.hovered(x + 65, by, 26, bh));
            Ui.buttonLabel(c, x + 65, by, 26, bh, fill ? "實心" : "空心", true);
            if (clickOn(x + 93, by, 26, bh)) fitZoom();
            Ui.button(c, x + 93, by, 26, bh, true, c.hovered(x + 93, by, 26, bh));
            Ui.buttonLabel(c, x + 93, by, 26, bh, "適配", true);
        }

        private void dialogText(PhoneCanvas c, PhoneStyle s, GuiGraphics g) {
            int px = x + 6, py = y + 50;
            Ui.fill(g, px, py, w - 12, 56, 0xFF202830);
            Ui.border(c, px, py, w - 12, 56, s.accentColor());
            Ui.text(c, "文字（Enter 確定）", px + 3, py + 2, s.titleColor());
            Ui.fill(g, px + 3, py + 15, w - 18, 12, 0xFF000000);
            Ui.textClipped(c, textInput + (System.currentTimeMillis() / 500 % 2 == 0 ? "▏" : ""),
                    px + 5, py + 16, s.bodyColor(), px, py, w - 12, 56);
            if (clickOn(px + 3, py + 31, 50, 11)) {
                if (!textInput.isEmpty()) {
                    texts.add(new TextObj(textX, textY, textInput, Math.max(8, brush * 4), curColor()));
                }
                typingText = false;
            }
            Ui.button(c, px + 3, py + 31, 50, 11, true, c.hovered(px + 3, py + 31, 50, 11));
            Ui.buttonLabel(c, px + 3, py + 31, 50, 11, "確定", true);
            if (clickOn(px + 57, py + 31, 50, 11)) typingText = false;
            Ui.button(c, px + 57, py + 31, 50, 11, true, c.hovered(px + 57, py + 31, 50, 11));
            Ui.buttonLabel(c, px + 57, py + 31, 50, 11, "取消", true);
        }

        private void dialogName(PhoneCanvas c, PhoneStyle s, GuiGraphics g) {
            int px = x + 6, py = y + 50;
            Ui.fill(g, px, py, w - 12, 56, 0xFF202830);
            Ui.border(c, px, py, w - 12, 56, s.accentColor());
            Ui.text(c, "檔名（Enter 確定）", px + 3, py + 2, s.titleColor());
            Ui.fill(g, px + 3, py + 15, w - 18, 12, 0xFF000000);
            Ui.textClipped(c, name + (System.currentTimeMillis() / 500 % 2 == 0 ? "▏" : ""),
                    px + 5, py + 16, s.bodyColor(), px, py, w - 12, 56);
            if (clickOn(px + 3, py + 31, 50, 11)) {
                naming = false;
                if (name.trim().isEmpty()) name = "畫作";
            }
            Ui.button(c, px + 3, py + 31, 50, 11, true, c.hovered(px + 3, py + 31, 50, 11));
            Ui.buttonLabel(c, px + 3, py + 31, 50, 11, "確定", true);
            if (clickOn(px + 57, py + 31, 50, 11)) naming = false;
            Ui.button(c, px + 57, py + 31, 50, 11, true, c.hovered(px + 57, py + 31, 50, 11));
            Ui.buttonLabel(c, px + 57, py + 31, 50, 11, "取消", true);
        }

        private void dialogNew(PhoneCanvas c, PhoneStyle s, GuiGraphics g) {
            int px = x + 6, py = y + 40;
            Ui.fill(g, px, py, w - 12, 62, 0xFF202830);
            Ui.border(c, px, py, w - 12, 62, s.accentColor());
            Ui.text(c, "新畫布大小", px + 3, py + 2, s.titleColor());
            int[][] sizes = { { 48, 32 }, { 96, 64 }, { 192, 128 }, { 384, 256 } };
            for (int i = 0; i < 4; i++) {
                int by2 = py + 14 + (i / 2) * 18;
                int bx2 = px + 3 + (i % 2) * 50;
                if (clickOn(bx2, by2, 48, 16)) {
                    newCanvas(sizes[i][0], sizes[i][1]);
                    newCanvasDialog = false;
                }
                Ui.button(c, bx2, by2, 48, 16, true, c.hovered(bx2, by2, 48, 16));
                Ui.buttonLabel(c, bx2, by2, 48, 16, sizes[i][0] + "×" + sizes[i][1], true);
            }
            if (clickOn(px + 3, py + 52, 48, 11)) newCanvasDialog = false;
            Ui.button(c, px + 3, py + 52, 48, 11, true, c.hovered(px + 3, py + 52, 48, 11));
            Ui.buttonLabel(c, px + 3, py + 52, 48, 11, "取消", true);
        }

        private void renderFiles(PhoneCanvas c, PhoneStyle s, GuiGraphics g) {
            Ui.fill(g, x, y, w, h, s.screenBackground());
            Ui.text(c, "📂 我的畫作", x + 3, y + 1, s.titleColor());
            Ui.hline(g, x, x + w, y + 10, s.buttonDisabledColor());
            List<String> files = listFiles();
            int listY = y + 12, listH = h - 24, rowH = 11;
            fileScroller.clamp(files.size() * rowH, listH);
            int off = (int) fileScroller.offset();
            for (int i = 0; i < files.size(); i++) {
                int ry = listY + i * rowH - off;
                if (ry + rowH < listY || ry > listY + listH) continue;
                if (clickOn(x, ry, w - 2, rowH)) {
                    load(files.get(i));
                    showFiles = false;
                }
                if (c.hovered(x, ry, w - 2, rowH)) Ui.fill(g, x, ry, w - 2, rowH, s.pressedOverlay());
                Ui.textClipped(c, "🖼 " + files.get(i), x + 3, ry + 1, s.bodyColor(), x, ry, w, rowH);
            }
            Ui.scrollbar(c, x + w - 3, listY, listH, files.size() * rowH, off);
            if (clickOn(x + 1, y + h - 12, w - 2, 11)) showFiles = false;
            Ui.button(c, x + 1, y + h - 12, w - 2, 11, true, c.hovered(x + 1, y + h - 12, w - 2, 11));
            Ui.buttonLabel(c, x + 1, y + h - 12, w - 2, 11, "← 返回", true);
        }
    }
}
