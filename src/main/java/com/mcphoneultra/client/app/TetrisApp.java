package com.mcphoneultra.client.app;

import com.mcphoneultra.client.io.Store;
import com.mcphoneultra.client.ui.Ui;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.Random;

/** 俄羅斯方塊：方向鍵移動/旋轉，下鍵加速，空格瞬落。商店可下載。 */
public final class TetrisApp extends BaseApp {

    public TetrisApp() {
        super("tetris", false);
    }

    @Override
    protected IPhonePage createPage() {
        return new TetrisPage();
    }

    private static final class TetrisPage extends ClickablePage {
        private static final int COLS = 10, ROWS = 20;
        // 7 種方塊的形狀（旋轉 0 度的 4×4 掩碼）
        private static final int[][][] SHAPES = {
                {{1, 1, 1, 1}},
                {{1, 1}, {1, 1}},
                {{0, 1, 0}, {1, 1, 1}},
                {{1, 0, 0}, {1, 1, 1}},
                {{0, 0, 1}, {1, 1, 1}},
                {{1, 1, 0}, {0, 1, 1}},
                {{0, 1, 1}, {1, 1, 0}},
        };
        private final int[][] board = new int[ROWS][COLS];
        private int[][] cur;
        private int curX, curY, curType;
        private int score, level;
        private boolean dead;
        private long lastDrop;
        private final Random rnd = new Random();
        private final Store store = Store.of("tetris");

        private void reset() {
            for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) board[r][c] = 0;
            score = 0;
            level = 0;
            dead = false;
            spawn();
        }

        private void spawn() {
            curType = rnd.nextInt(SHAPES.length);
            int[][] shape = SHAPES[curType];
            cur = new int[shape.length][];
            for (int i = 0; i < shape.length; i++) cur[i] = shape[i].clone();
            curX = COLS / 2 - cur[0].length / 2;
            curY = 0;
            if (collides(cur, curX, curY)) dead = true;
        }

        private boolean collides(int[][] shape, int ox, int oy) {
            for (int r = 0; r < shape.length; r++) {
                for (int c = 0; c < shape[r].length; c++) {
                    if (shape[r][c] == 0) continue;
                    int x = ox + c, y = oy + r;
                    if (x < 0 || x >= COLS || y >= ROWS) return true;
                    if (y >= 0 && board[y][x] != 0) return true;
                }
            }
            return false;
        }

        private void lock() {
            for (int r = 0; r < cur.length; r++) {
                for (int c = 0; c < cur[r].length; c++) {
                    if (cur[r][c] != 0) {
                        int y = curY + r;
                        if (y >= 0 && y < ROWS) board[y][curX + c] = curType + 1;
                    }
                }
            }
            clearLines();
            spawn();
        }

        private void clearLines() {
            int cleared = 0;
            for (int r = ROWS - 1; r >= 0; r--) {
                boolean full = true;
                for (int c = 0; c < COLS; c++) if (board[r][c] == 0) full = false;
                if (full) {
                    cleared++;
                    for (int rr = r; rr > 0; rr--) System.arraycopy(board[rr - 1], 0, board[rr], 0, COLS);
                    java.util.Arrays.fill(board[0], 0);
                    r++;
                }
            }
            if (cleared > 0) {
                score += new int[]{0, 100, 300, 500, 800}[cleared] * (level + 1);
                level += cleared / 2;
            }
        }

        private void dropMs() {
            int base = Math.max(60, 800 - level * 60);
        }

        private void step() {
            if (dead) return;
            if (!collides(cur, curX, curY + 1)) {
                curY++;
            } else {
                lock();
            }
        }

        private void rotate() {
            int h = cur[0].length, w = cur.length;
            int[][] rot = new int[h][w];
            for (int r = 0; r < w; r++) for (int c = 0; c < h; c++) rot[c][w - 1 - r] = cur[r][c];
            if (!collides(rot, curX, curY)) cur = rot;
        }

        private void hardDrop() {
            while (!collides(cur, curX, curY + 1)) curY++;
            score += 2;
            lock();
        }

        @Override
        public void onOpen() {
            reset();
        }

        @Override
        public void render(PhoneCanvas canvas) {
            int x = canvas.x(), y = canvas.y(), w = canvas.width(), h = canvas.height();
            var s = canvas.style();
            GuiGraphics g = canvas.graphics();
            Ui.fill(g, x, y, w, h, s.screenBackground());

            String status = dead ? "💀 堆到頂了！" : "分數 " + score + "  ·  Lv" + level;
            Ui.textClipped(canvas, status, x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            if (clickOn(x + w - 40, y + 13, 38, 12)) reset();
            Ui.button(canvas, x + w - 40, y + 13, 38, 12, true, canvas.hovered(x + w - 40, y + 13, 38, 12));
            Ui.buttonLabel(canvas, x + w - 40, y + 13, 38, 12, "重開", true);

            if (!dead && System.currentTimeMillis() - lastDrop > Math.max(60, 800 - level * 60)) {
                step();
                lastDrop = System.currentTimeMillis();
            }

            int cell = Math.max(6, Math.min((w - 30) / COLS, (h - 40) / ROWS));
            int bw = COLS * cell, bh = ROWS * cell;
            int bx = x + 3, by = y + 24;

            for (int r = 0; r < ROWS; r++) {
                for (int c = 0; c < COLS; c++) {
                    if (board[r][c] != 0) {
                        Ui.fill(g, bx + c * cell, by + r * cell, cell, cell, pieceColor(board[r][c] - 1));
                    }
                }
            }
            for (int r = 0; r < cur.length; r++) {
                for (int c = 0; c < cur[r].length; c++) {
                    if (cur[r][c] != 0) {
                        int py = curY + r;
                        if (py >= 0) {
                            Ui.fill(g, bx + (curX + c) * cell, by + py * cell, cell, cell, pieceColor(curType));
                        }
                    }
                }
            }
            Ui.border(canvas, bx, by, bw, bh, s.buttonDisabledColor());

            // 右側操作提示
            int tx = bx + bw + 4;
            Ui.text(canvas, "←→ 移動", tx, y + 30, s.subtleColor());
            Ui.text(canvas, "↑ 旋轉", tx, y + 44, s.subtleColor());
            Ui.text(canvas, "↓ 加速", tx, y + 58, s.subtleColor());
            Ui.text(canvas, "空格 瞬落", tx, y + 72, s.subtleColor());
        }

        private static int pieceColor(int t) {
            return switch (t % 7) {
                case 0 -> 0xFF4FC3F7;   // I 藍
                case 1 -> 0xFFFFD54F;   // O 黃
                case 2 -> 0xFFBA68C8;   // T 紫
                case 3 -> 0xFF4DB6AC;   // J 青
                case 4 -> 0xFFFF8A65;   // L 橙
                case 5 -> 0xFFE57373;   // S 紅
                default -> 0xFF81C784;  // Z 綠
            };
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (dead) return false;
            switch (key) {
                case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> {
                    if (!collides(cur, curX - 1, curY)) curX--;
                }
                case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> {
                    if (!collides(cur, curX + 1, curY)) curX++;
                }
                case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> rotate();
                case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> {
                    step();
                    score += 1;
                    lastDrop = System.currentTimeMillis();
                }
                case GLFW.GLFW_KEY_SPACE -> hardDrop();
                default -> {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void onClose() {
            int best = store.getInt("best", 0);
            if (score > best) store.setInt("best", score);
        }

        @Override
        public boolean capturesKeyboard() {
            return true;
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            return true;
        }
    }
}
