package com.mcphoneultra.client.app;

import com.mcphoneultra.client.io.Paths;
import com.mcphoneultra.client.io.Store;
import com.mcphoneultra.client.ui.Scroller;
import com.mcphoneultra.client.ui.TextBuffer;
import com.mcphoneultra.client.ui.Ui;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 共用的代码编辑器页：文件列表 + 多行编辑 + 运行 + 输出查看。
 * JS 编程与 Python 编程两个 App 复用，区别只在目录、扩展名与运行器。
 */
public final class CodeEditorPage extends ClickablePage {

    public interface Runner {
        /** 在后台线程执行，返回合并后的输出文本。 */
        String run(String code);
    }

    private final Path dir;
    private final String ext;
    private final Runner runner;

    private final TextBuffer buffer = new TextBuffer();
    private final Scroller fileScroller = new Scroller();
    private final Scroller outScroller = new Scroller();
    private Path currentFile;
    private boolean showFiles;
    private boolean showOutput;
    private String outputText = "";
    private boolean running;
    private String status = "";
    private String newName = "";
    private boolean naming;

    public CodeEditorPage(Path dir, String ext, Runner runner) {
        this.dir = dir;
        this.ext = ext;
        this.runner = runner;
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        // 打开时自动载入目录里第一个文件（若有）
        List<Path> files = listFiles();
        if (!files.isEmpty()) {
            load(files.get(0));
        } else {
            buffer.setText("// 新檔案\n");
            currentFile = dir.resolve("main" + ext);
        }
    }

    private List<Path> listFiles() {
        List<Path> out = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile).forEach(out::add);
        } catch (IOException ignored) {
        }
        out.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
        return out;
    }

    private void load(Path p) {
        currentFile = p;
        buffer.setText(Store.readAll(p).orElse(""));
        buffer.resetView();
        status = "";
    }

    private void save() {
        if (currentFile == null) return;
        Store.writeAll(currentFile, buffer.text());
        status = "已儲存 " + currentFile.getFileName();
    }

    private void doRun() {
        if (running) return;
        save();
        String code = buffer.text();
        running = true;
        showOutput = true;
        outputText = "";
        outScroller.reset();
        Thread t = new Thread(() -> {
            String out = runner.run(code);
            // 回主线程（UI 状态），用 Minecraft 的 execute
            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                outputText = out;
                running = false;
                outScroller.reset();
            });
        }, "mcphone-ultra-run");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void onClose() {
        save();
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

        if (showFiles) {
            renderFiles(c, x, y, w, h);
            return;
        }
        if (showOutput) {
            renderOutput(c, x, y, w, h);
            return;
        }

        String name = currentFile == null ? "未命名" : currentFile.getFileName().toString();
        Ui.textClipped(c, name, x + 3, y + 2, s.titleColor(), x, y, w, 12);
        Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

        int by = y + 13;
        int bh = 12;
        int bw = (w - 8) / 4;
        if (clickOn(x + 1, by, bw, bh)) {
            showFiles = true;
        }
        Ui.button(c, x + 1, by, bw, bh, true, c.hovered(x + 1, by, bw, bh));
        Ui.buttonLabel(c, x + 1, by, bw, bh, "開啟", true);
        if (clickOn(x + 2 + bw, by, bw, bh)) {
            save();
        }
        Ui.button(c, x + 2 + bw, by, bw, bh, true, c.hovered(x + 2 + bw, by, bw, bh));
        Ui.buttonLabel(c, x + 2 + bw, by, bw, bh, "儲存", true);
        if (clickOn(x + 3 + bw * 2, by, bw, bh)) {
            doRun();
        }
        Ui.button(c, x + 3 + bw * 2, by, bw, bh, !running, c.hovered(x + 3 + bw * 2, by, bw, bh));
        Ui.buttonLabel(c, x + 3 + bw * 2, by, bw, bh, running ? "執行中…" : "執行", !running);
        if (clickOn(x + 4 + bw * 3, by, bw, bh)) {
            showOutput = true;
            outputText = "（尚未執行）";
        }
        Ui.button(c, x + 4 + bw * 3, by, bw, bh, true, c.hovered(x + 4 + bw * 3, by, bw, bh));
        Ui.buttonLabel(c, x + 4 + bw * 3, by, bw, bh, "輸出", true);

        int ey = y + 29;
        int eh = h - 29 - 24;
        buffer.render(c, x + 2, ey, w - 4, eh, true);

        int ny = y + h - 22;
        if (clickOn(x + 1, ny, 30, 12)) {
            naming = true;
            newName = "新檔案";
        }
        Ui.button(c, x + 1, ny, 30, 12, true, c.hovered(x + 1, ny, 30, 12));
        Ui.buttonLabel(c, x + 1, ny, 30, 12, "新增", true);
        if (clickOn(x + 33, ny, 34, 12)) {
            buffer.setText("");
            buffer.resetView();
        }
        Ui.button(c, x + 33, ny, 34, 12, true, c.hovered(x + 33, ny, 34, 12));
        Ui.buttonLabel(c, x + 33, ny, 34, 12, "清空", true);
        if (clickOn(x + 69, ny, w - 70, 12)) {
            buffer.scrollV(3, 12);
        }
        Ui.button(c, x + 69, ny, w - 70, 12, true, c.hovered(x + 69, ny, w - 70, 12));
        Ui.buttonLabel(c, x + 69, ny, w - 70, 12, "↓ 下滾", true);

        if (!status.isEmpty()) {
            Ui.textClipped(c, status, x + 3, y + h - 12, s.subtleColor(), x, y, w, 14);
        }

        if (naming) {
            int px = x + 6;
            int py = y + 60;
            Ui.fill(g, px, py, w - 12, 56, 0xFF202830);
            Ui.border(c, px, py, w - 12, 56, s.accentColor());
            Ui.text(c, "檔名（含副檔名，Enter 確定）", px + 3, py + 3, s.titleColor());
            Ui.fill(g, px + 3, py + 16, w - 18, 12, 0xFF000000);
            Ui.textClipped(c, newName + (System.currentTimeMillis() / 500 % 2 == 0 ? "▏" : ""),
                    px + 5, py + 17, s.bodyColor(), px, py, w - 12, 56);
            if (clickOn(px + 3, py + 32, 50, 12)) {
                doNewFile();
            }
            Ui.button(c, px + 3, py + 32, 50, 12, true, c.hovered(px + 3, py + 32, 50, 12));
            Ui.buttonLabel(c, px + 3, py + 32, 50, 12, "確定", true);
            if (clickOn(px + 57, py + 32, 50, 12)) {
                naming = false;
            }
            Ui.button(c, px + 57, py + 32, 50, 12, true, c.hovered(px + 57, py + 32, 50, 12));
            Ui.buttonLabel(c, px + 57, py + 32, 50, 12, "取消", true);
        }
    }

    private void doNewFile() {
        naming = false;
        String n = newName.trim();
        if (n.isEmpty()) return;
        if (!n.toLowerCase(Locale.ROOT).endsWith(ext)) n = n + ext;
        Path p = dir.resolve(Paths.safeName(n));
        if (Files.exists(p)) {
            status = "已存在，直接開啟";
            load(p);
            return;
        }
        Store.writeAll(p, "");
        load(p);
        status = "已建立 " + p.getFileName();
    }

    private void renderFiles(PhoneCanvas c, int x, int y, int w, int h) {
        var s = c.style();
        GuiGraphics g = c.graphics();
        Ui.fill(g, x, y, w, h, s.screenBackground());
        Ui.text(c, "📂 檔案列表（" + ext + "）", x + 3, y + 2, s.titleColor());
        Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

        List<Path> files = listFiles();
        int listY = y + 14;
        int listH = h - 28;
        int rowH = 12;
        int contentH = files.size() * rowH;
        fileScroller.clamp(contentH, listH);
        int off = (int) fileScroller.offset();
        for (int i = 0; i < files.size(); i++) {
            int ry = listY + i * rowH - off;
            if (ry + rowH < listY || ry > listY + listH) continue;
            Path p = files.get(i);
            boolean selected = p.equals(currentFile);
            if (clickOn(x, ry, w - 2, rowH)) {
                load(p);
                showFiles = false;
                break;
            }
            if (c.hovered(x, ry, w - 2, rowH)) Ui.fill(g, x, ry, w - 2, rowH, s.pressedOverlay());
            Ui.textClipped(c, (selected ? "● " : "  ") + p.getFileName(), x + 3, ry + 1,
                    selected ? s.accentColor() : s.bodyColor(), x, ry, w, rowH);
            Ui.hline(g, x + 2, x + w - 2, ry + rowH - 1, s.buttonDisabledColor());
        }
        Ui.scrollbar(c, x + w - 3, listY, listH, contentH, off);

        int by = y + h - 14;
        if (clickOn(x + 1, by, w - 2, 12)) {
            showFiles = false;
        }
        Ui.button(c, x + 1, by, w - 2, 12, true, c.hovered(x + 1, by, w - 2, 12));
        Ui.buttonLabel(c, x + 1, by, w - 2, 12, "← 返回編輯", true);
    }

    private void renderOutput(PhoneCanvas c, int x, int y, int w, int h) {
        var s = c.style();
        GuiGraphics g = c.graphics();
        Ui.fill(g, x, y, w, h, s.screenBackground());
        Ui.text(c, running ? "執行中…" : "📤 輸出", x + 3, y + 2, s.titleColor());
        Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

        int ty = y + 14;
        int th = h - 28;
        int rowH = c.font().lineHeight + 1;
        String[] lines = outputText.split("\n", -1);
        int contentH = lines.length * rowH;
        outScroller.clamp(contentH, th);
        int off = (int) outScroller.offset();
        int startRow = off / rowH;
        int visible = th / rowH + 2;
        for (int i = startRow; i < Math.min(lines.length, startRow + visible); i++) {
            Ui.textClipped(c, lines[i], x + 3, ty + i * rowH - off, s.bodyColor(), x, ty, w - 4, th);
        }
        Ui.scrollbar(c, x + w - 3, ty, th, contentH, off);

        int by = y + h - 14;
        if (clickOn(x + 1, by, w - 2, 12)) {
            showOutput = false;
        }
        Ui.button(c, x + 1, by, w - 2, 12, true, c.hovered(x + 1, by, w - 2, 12));
        Ui.buttonLabel(c, x + 1, by, w - 2, 12, "← 返回編輯", true);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        if (showFiles) {
            fileScroller.onWheel(amount, listFiles().size() * 12, 130);
            return true;
        }
        if (showOutput) {
            outScroller.onWheel(amount, outputText.split("\n", -1).length * 10, 130);
            return true;
        }
        buffer.scrollV(amount > 0 ? -3 : 3, 12);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (naming) {
            if (key == GLFW.GLFW_KEY_ENTER) {
                doNewFile();
                return true;
            }
            if (key == GLFW.GLFW_KEY_BACKSPACE && !newName.isEmpty()) {
                newName = newName.substring(0, newName.length() - 1);
                return true;
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) return false;
        return buffer.onKey(key, scan, mods);
    }

    @Override
    public boolean charTyped(char ch, int mods) {
        if (naming) {
            if (ch >= 32 && ch != 127) newName += ch;
            return true;
        }
        if (showFiles || showOutput) return true;
        buffer.insert(ch);
        return true;
    }

    @Override
    public boolean capturesKeyboard() {
        return true;
    }

    @Override
    public boolean onBack() {
        if (naming) {
            naming = false;
            return true;
        }
        if (showFiles) {
            showFiles = false;
            return true;
        }
        if (showOutput) {
            showOutput = false;
            return true;
        }
        return false;
    }
}
