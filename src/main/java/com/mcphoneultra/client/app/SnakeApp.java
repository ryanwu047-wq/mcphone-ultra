package com.mcphoneultra.client.app;

import com.mcphoneultra.client.io.Store;
import com.mcphoneultra.client.ui.Ui;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/** 貪吃蛇：方向鍵控制，吃豆子長長，撞牆或咬到自己結束。最高分存檔。 */
public final class SnakeApp extends BaseApp {

    public SnakeApp() {
        super("snake", true);
    }

    @Override
    protected IPhonePage createPage() {
        return new SnakePage();
    }

    private static final class SnakePage extends ClickablePage {
        private static final int COLS = 15, ROWS = 15;
        private final Deque<int[]> body = new ArrayDeque<>();
        private int dirX = 1, dirY = 0, nextDirX = 1, nextDirY = 0;
        private int foodX, foodY;
        private boolean dead;
        private long lastStep;
        private int speedMs = 180;
        private final Random rnd = new Random();
        private final Store store = Store.of("snake");

        private void reset() {
            body.clear();
            body.add(new int[]{COLS / 2, ROWS / 2});
            dirX = 1; dirY = 0; nextDirX = 1; nextDirY = 0;
            dead = false;
            speedMs = 180;
            spawnFood();
            lastStep = 0;
        }

        private void spawnFood() {
            while (true) {
                int x = rnd.nextInt(COLS), y = rnd.nextInt(ROWS);
                boolean onBody = false;
                for (int[] p : body) if (p[0] == x && p[1] == y) onBody = true;
                if (!onBody) {
                    foodX = x;
                    foodY = y;
                    return;
                }
            }
        }

        private void step() {
            if (dead) return;
            int nx = body.peekFirst()[0] + nextDirX;
            int ny = body.peekFirst()[1] + nextDirY;
            if (nx < 0 || ny < 0 || nx >= COLS || ny >= ROWS) {
                dead = true;
                return;
            }
            boolean eat = nx == foodX && ny == foodY;
            for (int[] p : body) if (p[0] == nx && p[1] == ny) {
                dead = true;
                return;
            }
            body.addFirst(new int[]{nx, ny});
            if (eat) {
                spawnFood();
                speedMs = Math.max(70, speedMs - 4);
            } else {
                body.removeLast();
            }
            dirX = nextDirX;
            dirY = nextDirY;
        }

        private int score() {
            return body.size() - 1;
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

            int best = store.getInt("best", 0);
            String status = dead ? "💀 撞到了！本局 " + score()
                    : "🍎 分數 " + score() + "  ·  最高 " + best;
            Ui.textClipped(canvas, status, x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            if (clickOn(x + w - 40, y + 13, 38, 12)) reset();
            Ui.button(canvas, x + w - 40, y + 13, 38, 12, true, canvas.hovered(x + w - 40, y + 13, 38, 12));
            Ui.buttonLabel(canvas, x + w - 40, y + 13, 38, 12, "重開", true);

            // 死掉時按方向鍵重開
            if (dead && pendingDir != 0) {
                reset();
            }

            if (!dead && System.currentTimeMillis() - lastStep > speedMs) {
                step();
                lastStep = System.currentTimeMillis();
            }

            int area = Math.min(w - 8, h - 40);
            int cell = area / COLS;
            area = cell * COLS;
            int gx = x + (w - area) / 2;
            int gy = y + 24;

            // 食物
            Ui.circle(g, gx + foodX * cell + cell / 2, gy + foodY * cell + cell / 2,
                    Math.max(2, cell / 3), 0xFFFF6B6B);
            // 蛇
            int i = 0;
            for (int[] p : body) {
                Ui.fill(g, gx + p[0] * cell, gy + p[1] * cell, cell, cell,
                        i == 0 ? 0xFF81C784 : 0xFF4CAF50);
                i++;
            }
            Ui.border(canvas, gx, gy, area, area, s.buttonDisabledColor());
        }

        private int pendingDir = 0;

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            int d = switch (key) {
                case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> 1;
                case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> 2;
                case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> 3;
                case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> 4;
                default -> 0;
            };
            if (d == 0) return false;
            if (d == 1 && dirY != 1) { nextDirX = 0; nextDirY = -1; }
            if (d == 2 && dirX != -1) { nextDirX = 1; nextDirY = 0; }
            if (d == 3 && dirY != -1) { nextDirX = 0; nextDirY = 1; }
            if (d == 4 && dirX != 1) { nextDirX = -1; nextDirY = 0; }
            pendingDir = d;
            return true;
        }

        @Override
        public void onClose() {
            int best = store.getInt("best", 0);
            if (score() > best) store.setInt("best", score());
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
