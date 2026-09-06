package com.mcphoneultra.client.app;

import com.mcphoneultra.client.io.Paths;
import com.mcphoneultra.client.ui.Ui;
import com.mcphoneultra.client.util.Exec;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Termux 风格的终端：在 config/mcphone_ultra/termux/ 里开一个真实的 shell 会话
 * （Windows 用 cmd，其他平台用 sh），支持输入、历史、滚轮回看、快速命令。
 */
public final class TermuxApp extends BaseApp {

    public TermuxApp() {
        super("termux", true);
    }

    @Override
    protected IPhonePage createPage() {
        return new TermuxPage();
    }

    private static final class TermuxPage extends ClickablePage {

        private static final int MAX_OUT = 24_000;

        private final Path home = Paths.dir("termux");
        private final StringBuilder out = new StringBuilder();
        private final Deque<String> history = new ArrayDeque<>();
        private int historyIndex;
        private StringBuilder input = new StringBuilder();
        private Process process;
        private OutputStream stdin;
        private final ScrollerLines scroll = new ScrollerLines();
        private String status = "Termux · " + home;

        private static final class ScrollerLines {
            float offset;
        }

        @Override
        public void onOpen() {
            try {
                process = Exec.startShell(home);
                stdin = process.getOutputStream();
                Exec.pump(process.getInputStream(), out, MAX_OUT);
                appendLine("==== MCphone Termux ====");
                appendLine("工作目錄: " + home);
                appendLine("輸入指令按 Enter；↑↓ 翻歷史；滾輪回看。");
            } catch (IOException e) {
                appendLine("啟動 shell 失敗: " + e.getMessage());
            }
        }

        @Override
        public void onClose() {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Exception ignored) {
                }
                process = null;
            }
        }

        private void appendLine(String s) {
            synchronized (out) {
                out.append(s).append('\n');
                if (out.length() > MAX_OUT) out.delete(0, out.length() - MAX_OUT);
            }
        }

        private void send(String line) {
            appendLine("> " + line);
            if (history.isEmpty() || !history.peekLast().equals(line)) history.addLast(line);
            historyIndex = history.size();
            try {
                if (stdin != null) {
                    stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    stdin.flush();
                }
            } catch (IOException e) {
                appendLine("（寫入失敗）");
            }
            input.setLength(0);
        }

        @Override
        public void render(PhoneCanvas c) {
            int x = c.x();
            int y = c.y();
            int w = c.width();
            int h = c.height();
            var s = c.style();
            GuiGraphics g = c.graphics();
            Ui.fill(g, x, y, w, h, 0xFF0A0E14);

            Ui.textClipped(c, "⌨ " + status, x + 3, y + 2, 0xFF8AC6FF, x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, 0xFF24303C);

            // 输出区
            int ty = y + 13;
            int th = h - 13 - 40;
            int rowH = c.font().lineHeight + 1;
            String[] lines;
            synchronized (out) {
                lines = out.toString().split("\n", -1);
            }
            int contentH = lines.length * rowH;
            int maxOff = Math.max(0, contentH - th);
            if (scroll.offset > maxOff) scroll.offset = maxOff;
            if (scroll.offset < 0) scroll.offset = 0;
            int off = (int) scroll.offset;
            int startRow = off / rowH;
            int visible = th / rowH + 2;
            for (int i = startRow; i < Math.min(lines.length, startRow + visible); i++) {
                Ui.textClipped(c, lines[i], x + 3, ty + i * rowH - off, 0xFFC8D8E8, x, ty, w - 4, th);
            }

            // 输入行
            int iy = y + h - 26;
            Ui.fill(g, x + 1, iy, w - 2, 14, 0xFF101820);
            Ui.border(c, x + 1, iy, w - 2, 14, 0xFF3A4A58);
            String prompt = "❯ ";
            String in = input.toString();
            if (in.length() > 16) in = in.substring(in.length() - 16);
            String shown = prompt + in + (System.currentTimeMillis() / 500 % 2 == 0 ? "▏" : "");
            Ui.textClipped(c, shown, x + 4, iy + 2, 0xFFE8F4FF, x, iy, w - 6, 14);

            // 快捷按钮
            int by = y + h - 12;
            int bw = (w - 6) / 5;
            String[] quick = {"pwd", "ls", "cls", "↑", "↓"};
            for (int i = 0; i < 5; i++) {
                int bx = x + 1 + i * (bw + 1);
                if (clickOn(bx, by, bw, 11)) {
                    switch (i) {
                        case 0 -> send("pwd");
                        case 1 -> send(Exec.WINDOWS ? "dir" : "ls");
                        case 2 -> {
                            synchronized (out) {
                                out.setLength(0);
                            }
                            scroll.offset = 0;
                        }
                        case 3 -> scroll.offset = Math.max(0, scroll.offset - rowH * 5);
                        case 4 -> scroll.offset += rowH * 5;
                    }
                }
                Ui.button(c, bx, by, bw, 11, true, c.hovered(bx, by, bw, 11));
                Ui.buttonLabel(c, bx, by, bw, 11, quick[i], true);
            }
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            scroll.offset -= (float) amount * 30;
            return true;
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                send(input.toString());
                return true;
            }
            if (key == GLFW.GLFW_KEY_BACKSPACE && input.length() > 0) {
                input.deleteCharAt(input.length() - 1);
                return true;
            }
            if (key == GLFW.GLFW_KEY_UP) {
                if (!history.isEmpty()) {
                    historyIndex = Math.max(0, historyIndex - 1);
                    input.setLength(0);
                    input.append(new java.util.ArrayList<>(history).get(historyIndex));
                }
                return true;
            }
            if (key == GLFW.GLFW_KEY_DOWN) {
                historyIndex = Math.min(history.size(), historyIndex + 1);
                input.setLength(0);
                if (historyIndex < history.size()) {
                    input.append(new java.util.ArrayList<>(history).get(historyIndex));
                }
                return true;
            }
            return false;
        }

        @Override
        public boolean charTyped(char ch, int mods) {
            if (ch >= 32 && ch != 127) input.append(ch);
            return true;
        }

        @Override
        public boolean capturesKeyboard() {
            return true;
        }
    }
}
