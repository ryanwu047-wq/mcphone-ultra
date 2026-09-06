package com.mcphoneultra.client.ui;

import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * 多行文字编辑缓冲：JS/Python 编辑器共用。
 * 平面字符串 + 光标下标，渲染时按行切分、支持垂直滚动与水平滚动。
 */
public final class TextBuffer {

    private final StringBuilder sb = new StringBuilder();
    private int cursor;
    private int hScroll;       // 水平滚动（像素）
    private boolean dirty = true;
    private List<String> lines = new ArrayList<>();

    public TextBuffer() {
        recompute();
    }

    public TextBuffer(String initial) {
        sb.append(initial == null ? "" : initial);
        cursor = sb.length();
        recompute();
    }

    public String text() {
        return sb.toString();
    }

    public void setText(String s) {
        sb.setLength(0);
        sb.append(s == null ? "" : s);
        cursor = sb.length();
        hScroll = 0;
        dirty = true;
    }

    public int cursor() {
        return cursor;
    }

    public void setCursor(int pos) {
        cursor = Math.max(0, Math.min(sb.length(), pos));
    }

    private void recompute() {
        if (!dirty) return;
        lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= sb.length(); i++) {
            if (i == sb.length() || sb.charAt(i) == '\n') {
                lines.add(sb.substring(start, i));
                start = i + 1;
            }
        }
        dirty = false;
    }

    public int lineCount() {
        recompute();
        return lines.size();
    }

    public String line(int i) {
        recompute();
        return i >= 0 && i < lines.size() ? lines.get(i) : "";
    }

    public void insert(char ch) {
        sb.insert(cursor, ch);
        cursor++;
        dirty = true;
    }

    public void insert(String s) {
        if (s == null || s.isEmpty()) return;
        sb.insert(cursor, s);
        cursor += s.length();
        dirty = true;
    }

    public void newline() {
        sb.insert(cursor, '\n');
        cursor++;
        dirty = true;
    }

    public void backspace() {
        if (cursor <= 0) return;
        sb.deleteCharAt(cursor - 1);
        cursor--;
        dirty = true;
    }

    public void delete() {
        if (cursor >= sb.length()) return;
        sb.deleteCharAt(cursor);
        dirty = true;
    }

    public void left() {
        if (cursor > 0) cursor--;
    }

    public void right() {
        if (cursor < sb.length()) cursor++;
    }

    public void home() {
        recompute();
        cursor = lineStart(lineIndexOf(cursor));
    }

    public void end() {
        recompute();
        cursor = lineEnd(lineIndexOf(cursor));
    }

    public void up() {
        recompute();
        int li = lineIndexOf(cursor);
        int col = cursor - lineStart(li);
        if (li <= 0) {
            cursor = 0;
            return;
        }
        int prevStart = lineStart(li - 1);
        int prevLen = lineEnd(li - 1) - prevStart;
        cursor = prevStart + Math.min(col, prevLen);
    }

    public void down() {
        recompute();
        int li = lineIndexOf(cursor);
        int col = cursor - lineStart(li);
        if (li >= lines.size() - 1) {
            cursor = sb.length();
            return;
        }
        int nextStart = lineStart(li + 1);
        int nextLen = lineEnd(li + 1) - nextStart;
        cursor = nextStart + Math.min(col, nextLen);
    }

    private int lineIndexOf(int idx) {
        recompute();
        int count = 0;
        for (int i = 0; i < sb.length() && i < idx; i++) {
            if (sb.charAt(i) == '\n') count++;
        }
        return count;
    }

    private int lineStart(int li) {
        recompute();
        if (li <= 0) return 0;
        int count = 0;
        for (int i = 0; i < sb.length(); i++) {
            if (sb.charAt(i) == '\n') {
                count++;
                if (count == li) return i + 1;
            }
        }
        return 0;
    }

    private int lineEnd(int li) {
        recompute();
        int start = lineStart(li);
        for (int i = start; i < sb.length(); i++) {
            if (sb.charAt(i) == '\n') return i;
        }
        return sb.length();
    }

    /**
     * 在页面上画编辑区。返回内容高度（像素）。
     * clip 区域即编辑区本身；光标行会跟随自动滚动。
     */
    public int render(PhoneCanvas c, int x, int y, int w, int h, boolean focused) {
        recompute();
        Font f = c.font();
        GuiGraphics g = c.graphics();
        var s = c.style();
        int rowH = f.lineHeight + 2;

        // 让光标所在行保持可见（垂直滚动按行计）
        int li = lineIndexOf(cursor);
        int cursorY = y + li * rowH;
        int vOffset = this.vOffset;
        if (cursorY < y) vOffset = li;
        if (cursorY + rowH > y + h) vOffset = Math.max(0, li - h / rowH + 1);
        this.vOffset = Math.max(0, vOffset);

        Ui.fill(g, x, y, w, h, s.screenBackground());
        Ui.border(c, x, y, w, h, s.buttonDisabledColor());

        int visible = h / rowH;
        for (int i = vOffset; i < vOffset + visible && i < lines.size(); i++) {
            String line = lines.get(i);
            int ty = y + (i - vOffset) * rowH + 1;
            Ui.textClipped(c, line, x + 4 - hScroll, ty + 1, s.bodyColor(), x + 2, y, w - 4, h);
            // 光标
            if (focused && i == li) {
                int colPx = f.width(line.substring(0, Math.min(colOf(cursor), line.length())));
                int cx = x + 4 - hScroll + colPx;
                if (cx >= x + 2 && cx < x + w - 2) {
                    Ui.vline(g, cx, ty, ty + rowH - 1, s.accentColor());
                }
                // 水平滚动：光标跑到右边界外就推一屏
                if (colPx - hScroll > w - 12) hScroll = colPx - (w - 16);
                if (colPx - hScroll < 0) hScroll = Math.max(0, colPx - 4);
            }
        }
        return lines.size() * rowH;
    }

    private int colOf(int idx) {
        recompute();
        int start = lineStart(lineIndexOf(idx));
        return idx - start;
    }

    private int vOffset;

    public void scrollV(int rows, int viewRows) {
        vOffset = Math.max(0, Math.min(vOffset + rows, Math.max(0, lineCount() - viewRows)));
    }

    public void resetView() {
        vOffset = 0;
        hScroll = 0;
    }

    public boolean onKey(int key, int scan, int mods) {
        switch (key) {
            case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT -> left();
            case org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT -> right();
            case org.lwjgl.glfw.GLFW.GLFW_KEY_UP -> up();
            case org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN -> down();
            case org.lwjgl.glfw.GLFW.GLFW_KEY_HOME -> home();
            case org.lwjgl.glfw.GLFW.GLFW_KEY_END -> end();
            case org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE -> backspace();
            case org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE -> delete();
            case org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER -> newline();
            case org.lwjgl.glfw.GLFW.GLFW_KEY_TAB -> insert("    ");
            default -> {
                return false;
            }
        }
        return true;
    }
}
