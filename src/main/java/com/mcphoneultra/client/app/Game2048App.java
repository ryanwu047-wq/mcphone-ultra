package com.mcphoneultra.client.app;

import com.mcphoneultra.client.ui.Ui;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.Random;

/** 2048：方向鍵移動，相同數字合併。 */
public final class Game2048App extends BaseApp {

    public Game2048App() {
        super("game2048", true);
    }

    @Override
    protected IPhonePage createPage() {
        return new Game2048Page();
    }

    private static final class Game2048Page extends ClickablePage {
        private static final int SIZE = 4;
        private final long[][] grid = new long[SIZE][SIZE];
        private long score;
        private boolean won, lost;
        private final Random rnd = new Random();
        private long lastMove;

        private void reset() {
            for (int r = 0; r < SIZE; r++) for (int c = 0; c < SIZE; c++) grid[r][c] = 0;
            score = 0;
            won = lost = false;
            addTile();
            addTile();
        }

        private void addTile() {
            int free = 0;
            for (int r = 0; r < SIZE; r++) for (int c = 0; c < SIZE; c++) if (grid[r][c] == 0) free++;
            if (free == 0) return;
            int pick = rnd.nextInt(free);
            for (int r = 0; r < SIZE; r++) {
                for (int c = 0; c < SIZE; c++) {
                    if (grid[r][c] == 0 && pick-- == 0) {
                        grid[r][c] = rnd.nextInt(10) == 0 ? 4 : 2;
                        return;
                    }
                }
            }
        }

        /** 向 dir 移動一輪（0=上 1=右 2=下 3=左）。回傳是否真的動了。 */
        private boolean move(int dir) {
            long[][] next = new long[SIZE][SIZE];
            boolean moved = false;
            long gained = 0;
            for (int line = 0; line < SIZE; line++) {
                long[] row = new long[SIZE];
                int n = 0;
                for (int i = 0; i < SIZE; i++) {
                    int r = switch (dir) {
                        case 0 -> i;
                        case 1 -> SIZE - 1 - i;
                        case 2 -> SIZE - 1 - i;
                        default -> i;
                    };
                    int c = switch (dir) {
                        case 0 -> line;
                        case 1 -> line;
                        case 2 -> line;
                        default -> line;
                    };
                    long v = grid[r][c];
                    if (v != 0) row[n++] = v;
                }
                int out = 0;
                for (int i = 0; i < n; i++) {
                    if (i + 1 < n && row[i] == row[i + 1]) {
                        next[switch (dir) {case 0 -> out; case 1 -> SIZE - 1 - out; case 2 -> SIZE - 1 - out; default -> out;}]
                                [switch (dir) {case 0 -> line; case 1 -> line; case 2 -> line; default -> line;}] = row[i] * 2;
                        gained += row[i] * 2;
                        i++;
                        out++;
                        moved = true;
                    } else {
                        next[switch (dir) {case 0 -> out; case 1 -> SIZE - 1 - out; case 2 -> SIZE - 1 - out; default -> out;}]
                                [switch (dir) {case 0 -> line; case 1 -> line; case 2 -> line; default -> line;}] = row[i];
                        out++;
                    }
                }
            }
            for (int r = 0; r < SIZE; r++) {
                for (int c = 0; c < SIZE; c++) {
                    if (grid[r][c] != next[r][c]) moved = true;
                    grid[r][c] = next[r][c];
                }
            }
            if (moved) {
                score += gained;
                addTile();
            }
            check();
            return moved;
        }

        private void check() {
            won = lost = false;
            for (int r = 0; r < SIZE; r++) {
                for (int c = 0; c < SIZE; c++) {
                    if (grid[r][c] == 2048) won = true;
                    if (grid[r][c] == 0) return;
                }
            }
            for (int r = 0; r < SIZE; r++) {
                for (int c = 0; c < SIZE; c++) {
                    long v = grid[r][c];
                    if ((r + 1 < SIZE && grid[r + 1][c] == v) || (c + 1 < SIZE && grid[r][c + 1] == v)) return;
                }
            }
            lost = true;
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

            String status = lost ? "💀 沒格子了" : won ? "🏆 2048 達成！" : "分數 " + score;
            Ui.textClipped(canvas, status, x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            if (clickOn(x + w - 40, y + 13, 38, 12)) reset();
            Ui.button(canvas, x + w - 40, y + 13, 38, 12, true, canvas.hovered(x + w - 40, y + 13, 38, 12));
            Ui.buttonLabel(canvas, x + w - 40, y + 13, 38, 12, "重開", true);

            // 每 140ms 處理一次方向鍵（避免 GLFW 鍵重複太快）
            if (System.currentTimeMillis() - lastMove > 140 && !lost) {
                int dir = -1;
                if (canvas.hoveredContent()) {
                    // 由 keyPressed 設定的方向狀態處理
                }
                if (pendingDir != -1) {
                    dir = pendingDir;
                    pendingDir = -1;
                }
                if (dir >= 0) {
                    move(dir);
                    lastMove = System.currentTimeMillis();
                }
            }

            int area = Math.min(w - 8, h - 40);
            int cell = area / SIZE;
            area = cell * SIZE;
            int gx = x + (w - area) / 2;
            int gy = y + 24;

            for (int r = 0; r < SIZE; r++) {
                for (int c = 0; c < SIZE; c++) {
                    int cx = gx + c * cell, cy = gy + r * cell;
                    Ui.fill(g, cx, cy, cell, cell, tileColor(grid[r][c]));
                    Ui.border(canvas, cx, cy, cell, cell, 0xFF1A2028);
                    if (grid[r][c] != 0) {
                        Ui.drawCentered(canvas, String.valueOf(grid[r][c]), cx, cy, cell, cell, 0xFF101418);
                    }
                }
            }
        }

        private int pendingDir = -1;

        private static int tileColor(long v) {
            return switch ((int) Math.min(11, Math.log(v > 0 ? v : 2) / Math.log(2))) {
                case 1 -> 0xFFEEE4DA;
                case 2 -> 0xFFEDE0C8;
                case 3 -> 0xFFF2B179;
                case 4 -> 0xFFF59563;
                case 5 -> 0xFFF67C5F;
                case 6 -> 0xFFF65E3B;
                case 7 -> 0xFFEDCF72;
                case 8 -> 0xFFEDCC61;
                case 9 -> 0xFFEDC850;
                default -> 0xFFEDC22E;
            };
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            int dir = switch (key) {
                case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> 0;
                case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> 1;
                case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> 2;
                case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> 3;
                default -> -1;
            };
            if (dir >= 0) {
                pendingDir = dir;
                return true;
            }
            return false;
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
