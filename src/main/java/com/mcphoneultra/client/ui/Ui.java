package com.mcphoneultra.client.ui;

import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** 手机 UI 的通用绘制小件：按钮、文字裁剪、滚动条、简单图形。 */
public final class Ui {

    private Ui() {}

    public static boolean hit(int x, int y, int w, int h, int mx, int my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public static void fill(GuiGraphics g, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0) return;
        g.fill(x, y, x + w, y + h, color);
    }

    /** 画一个按钮底。hover 表示鼠标停着，enabled 为 false 时画灰。 */
    public static void button(PhoneCanvas c, int x, int y, int w, int h, boolean enabled, boolean hover) {
        var s = c.style();
        int bg = !enabled ? s.buttonDisabledColor() : hover ? s.buttonHoverColor() : s.buttonColor();
        fill(c.graphics(), x, y, w, h, bg);
        fill(c.graphics(), x, y, w, 1, 0xFF000000 | (s.buttonColor() & 0xFFFFFF));
    }

    public static void buttonLabel(PhoneCanvas c, int x, int y, int w, int h, String label, boolean enabled) {
        var s = c.style();
        int color = !enabled ? s.buttonDisabledTextColor() : s.titleColor();
        drawCentered(c, label, x, y, w, h, color);
    }

    /** 按钮一体化：画底 + 居中字。返回是否在区域内（点击判定由调用方做）。 */
    public static boolean buttonFull(PhoneCanvas c, int x, int y, int w, int h, String label, boolean enabled) {
        boolean hover = c.hovered(x, y, w, h);
        button(c, x, y, w, h, enabled, hover);
        buttonLabel(c, x, y, w, h, label, enabled);
        return hit(x, y, w, h, c.mouseX(), c.mouseY());
    }

    public static void drawCentered(PhoneCanvas c, String text, int x, int y, int w, int h, int color) {
        Font f = c.font();
        GuiGraphics g = c.graphics();
        int tw = f.width(text);
        g.drawString(f, text, x + (w - tw) / 2, y + (h - f.lineHeight) / 2, color, false);
    }

    public static void text(PhoneCanvas c, String text, int x, int y, int color) {
        c.graphics().drawString(c.font(), text, x, y, color, false);
    }

    public static void textClipped(PhoneCanvas c, String text, int x, int y, int color,
                                   int clipX, int clipY, int clipW, int clipH) {
        Font f = c.font();
        GuiGraphics g = c.graphics();
        int w = f.width(text);
        if (x + w < clipX || x > clipX + clipW) return;
        // 只画落在裁剪区内的部分：从可视起点切片
        int start = 0;
        while (start < text.length() && x + f.width(text.substring(0, start)) < clipX) start++;
        if (start >= text.length()) return;
        int end = start;
        while (end < text.length() && x + f.width(text.substring(0, end + 1)) <= clipX + clipW) end++;
        if (end <= start) return;
        g.drawString(f, text.substring(start, end), Math.max(x, clipX), y, color, false);
    }

    /** 在裁剪区内画一行居中文字 */
    public static void centerClipped(PhoneCanvas c, String text, int x, int y, int w, int h, int color,
                                     int clipX, int clipY, int clipW, int clipH) {
        int tw = c.font().width(text);
        int tx = x + (w - tw) / 2;
        int ty = y + (h - c.font().lineHeight) / 2;
        textClipped(c, text, tx, ty, color, clipX, clipY, clipW, clipH);
    }

    public static void border(PhoneCanvas c, int x, int y, int w, int h, int color) {
        GuiGraphics g = c.graphics();
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    public static void hline(GuiGraphics g, int x1, int x2, int y, int color) {
        g.fill(x1, y, x2, y + 1, color);
    }

    public static void vline(GuiGraphics g, int x, int y1, int y2, int color) {
        g.fill(x, y1, x + 1, y2, color);
    }

    /** 粗略画一个实心圆（用小方块逼近），中心 cx,cy 半径 r */
    public static void circle(GuiGraphics g, int cx, int cy, int r, int color) {
        int rr = r * r;
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dy * dy <= rr) {
                    g.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
                }
            }
        }
    }

    /** 实心三角形（顶点朝上），底边 y+r，中心 (cx,cy) */
    public static void triangle(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int half = (int) ((1.0 - Math.abs(dy) / (double) (r + 1)) * r);
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
        }
    }

    /** 菱形（旋转方块） */
    public static void diamond(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int half = r - Math.abs(dy);
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
        }
    }

    /** 四角星：中心十字 + 对角小方块 */
    public static void star(GuiGraphics g, int cx, int cy, int r, int color) {
        g.fill(cx - 1, cy - r, cx + 2, cy + r + 1, color);
        g.fill(cx - r, cy - 1, cx + r + 1, cy + 2, color);
    }

    /** 画滚动条。contentH 为总内容高，viewH 为可视高。 */
    public static void scrollbar(PhoneCanvas c, int x, int y, int viewH, int contentH, float offset) {
        if (contentH <= viewH) return;
        var s = c.style();
        int track = viewH - 2;
        int thumb = Math.max(8, track * viewH / contentH);
        float maxOff = Math.max(1, contentH - viewH);
        int ty = y + 1 + (int) ((track - thumb) * offset / maxOff);
        fill(c.graphics(), x, ty, 2, thumb, s.accentColor());
    }
}
