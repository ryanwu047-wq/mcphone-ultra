package com.mcphoneultra.client.app;

import com.mcphoneultra.client.ui.Ui;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.gui.GuiGraphics;

import java.util.Random;

/** 掃雷：9×9、10 顆地雷。左鍵掀開、右鍵插旗。 */
public final class MinesweeperApp extends BaseApp {

    public MinesweeperApp() {
        super("minesweeper", true);
    }

    @Override
    protected IPhonePage createPage() {
        return new MinesweeperPage();
    }

    private static final class MinesweeperPage extends ClickablePage {
        private static final int COLS = 9, ROWS = 9, MINES = 10;
        private final int[][] grid = new int[ROWS][COLS];   // -1 = 地雷，0..8 = 鄰近地雷數
        private final boolean[][] opened = new boolean[ROWS][COLS];
        private final boolean[][] flagged = new boolean[ROWS][COLS];
        private boolean lost, won, firstClick = true;
        private long startTime;
        private int flags;
        private final Random rnd = new Random();

        private void reset() {
            for (int r = 0; r < ROWS; r++) {
                for (int c = 0; c < COLS; c++) {
                    grid[r][c] = 0;
                    opened[r][c] = false;
                    flagged[r][c] = false;
                }
            }
            lost = won = false;
            firstClick = true;
            flags = 0;
        }

        private void placeMines(int safeR, int safeC) {
            int placed = 0;
            while (placed < MINES) {
                int r = rnd.nextInt(ROWS), c = rnd.nextInt(COLS);
                if (grid[r][c] == -1 || (Math.abs(r - safeR) <= 1 && Math.abs(c - safeC) <= 1)) continue;
                grid[r][c] = -1;
                placed++;
            }
            for (int r = 0; r < ROWS; r++) {
                for (int c = 0; c < COLS; c++) {
                    if (grid[r][c] == -1) continue;
                    grid[r][c] = count(r, c);
                }
            }
        }

        private int count(int r, int c) {
            int n = 0;
            for (int dr = -1; dr <= 1; dr++) {
                for (int dc = -1; dc <= 1; dc++) {
                    int rr = r + dr, cc = c + dc;
                    if (rr >= 0 && rr < ROWS && cc >= 0 && cc < COLS && grid[rr][cc] == -1) n++;
                }
            }
            return n;
        }

        private void reveal(int r, int c) {
            if (r < 0 || r >= ROWS || c < 0 || c >= COLS || opened[r][c] || flagged[r][c]) return;
            opened[r][c] = true;
            if (grid[r][c] == -1) {
                lost = true;
                return;
            }
            if (grid[r][c] == 0) {
                for (int dr = -1; dr <= 1; dr++) {
                    for (int dc = -1; dc <= 1; dc++) reveal(r + dr, c + dc);
                }
            }
        }

        private void checkWin() {
            int closed = 0;
            for (int r = 0; r < ROWS; r++) {
                for (int c = 0; c < COLS; c++) {
                    if (!opened[r][c]) closed++;
                }
            }
            won = !lost && closed == MINES;
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

            String status = lost ? "💥 踩雷了！"
                    : won ? "🎉 你贏了！" : "剩餘旗子 " + (MINES - flags)
                    + (firstClick ? "" : "  ·  " + (System.currentTimeMillis() - startTime) / 1000 + "s");
            Ui.textClipped(canvas, status, x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            if (clickOn(x + w - 40, y + 13, 38, 12)) reset();
            Ui.button(canvas, x + w - 40, y + 13, 38, 12, true, canvas.hovered(x + w - 40, y + 13, 38, 12));
            Ui.buttonLabel(canvas, x + w - 40, y + 13, 38, 12, "重開", true);

            int area = Math.min(w - 8, h - 40);
            int cell = area / COLS;
            area = cell * COLS;
            int gx = x + (w - area) / 2;
            int gy = y + 24;

            for (int r = 0; r < ROWS; r++) {
                for (int c = 0; c < COLS; c++) {
                    int cx = gx + c * cell, cy = gy + r * cell;
                    boolean hover = canvas.hovered(cx, cy, cell, cell);
                    if (clickOn(cx, cy, cell, cell)) {
                        if (!lost && !won) {
                            if (firstClick) {
                                startTime = System.currentTimeMillis();
                                placeMines(r, c);
                                firstClick = false;
                                reveal(r, c);
                                checkWin();
                            } else {
                                reveal(r, c);
                                checkWin();
                            }
                        }
                    }
                    if (rightClickOn(cx, cy, cell, cell)) {
                        if (!lost && !won && !opened[r][c]) {
                            flagged[r][c] = !flagged[r][c];
                            flags += flagged[r][c] ? 1 : -1;
                        }
                    }
                    Ui.fill(g, cx, cy, cell, cell,
                            opened[r][c] ? 0xFF3A4553 : (hover ? 0xFF465363 : 0xFF2C3542));
                    Ui.border(canvas, cx, cy, cell, cell, 0xFF1A2028);
                    if (flagged[r][c]) {
                        Ui.triangle(g, cx + cell / 2, cy + cell / 2, Math.max(2, cell / 3), 0xFFFF5555);
                    } else if (opened[r][c] && grid[r][c] > 0) {
                        Ui.drawCentered(canvas, String.valueOf(grid[r][c]), cx, cy, cell, cell, cellColor(grid[r][c]));
                    } else if (opened[r][c] && grid[r][c] == -1) {
                        Ui.circle(g, cx + cell / 2, cy + cell / 2, Math.max(2, cell / 4), 0xFF000000);
                    }
                }
            }
        }

        private static int cellColor(int n) {
            return switch (n) {
                case 1 -> 0xFF4FC3F7;
                case 2 -> 0xFF81C784;
                case 3 -> 0xFFFF8A65;
                case 4 -> 0xFFBA68C8;
                default -> 0xFFFFD54F;
            };
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            return true;
        }
    }
}
