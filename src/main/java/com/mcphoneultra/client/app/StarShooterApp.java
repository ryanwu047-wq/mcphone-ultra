package com.mcphoneultra.client.app;

import com.mcphoneultra.client.io.Store;
import com.mcphoneultra.client.ui.Ui;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** 太空射擊：←→移動、空格射擊，躲隕石打飛碟。商店可下載。 */
public final class StarShooterApp extends BaseApp {

    public StarShooterApp() {
        super("starshooter", false);
    }

    @Override
    protected IPhonePage createPage() {
        return new StarShooterPage();
    }

    private static final class StarShooterPage extends ClickablePage {
        private int playerX, playerY;
        private int score;
        private boolean dead;
        private long lastShot, lastSpawn, lastTick;
        private final List<int[]> bullets = new ArrayList<>();   // x,y
        private final List<int[]> rocks = new ArrayList<>();    // x,y,r,vy
        private final List<int[]> ufos = new ArrayList<>();     // x,y,dir
        private final Random rnd = new Random();
        private final Store store = Store.of("shooter");
        private int width = 100, height = 140;

        private void reset() {
            playerX = width / 2;
            playerY = height - 12;
            score = 0;
            dead = false;
            bullets.clear();
            rocks.clear();
            ufos.clear();
            lastTick = 0;
        }

        private void tick() {
            if (dead) return;
            if (moveLeft) playerX = Math.max(2, playerX - 3);
            if (moveRight) playerX = Math.min(width - 2, playerX + 3);
            if (fireHeld && System.currentTimeMillis() - lastShot > 180) {
                bullets.add(new int[]{playerX, playerY - 2});
                lastShot = System.currentTimeMillis();
            }
            for (int[] b : bullets) b[1] -= 5;
            bullets.removeIf(b -> b[1] < 0);

            if (System.currentTimeMillis() - lastSpawn > 500) {
                lastSpawn = System.currentTimeMillis();
                rocks.add(new int[]{rnd.nextInt(Math.max(1, width - 8)) + 4, -6, rnd.nextInt(4) + 3, rnd.nextInt(2) + 1});
                if (rnd.nextInt(3) == 0) {
                    ufos.add(new int[]{rnd.nextBoolean() ? 2 : width - 2, 8, rnd.nextBoolean() ? 1 : -1});
                }
            }
            for (int[] r : rocks) {
                r[1] += r[3];
                r[0] += rnd.nextInt(3) - 1;
                r[0] = Math.max(2, Math.min(width - 2, r[0]));
            }
            for (int[] u : ufos) {
                u[0] += u[2];
                u[1] += 1;
                if (u[0] <= 2 || u[0] >= width - 2) u[2] = -u[2];
            }
            rocks.removeIf(r -> r[1] > height + 8);
            ufos.removeIf(u -> u[1] > height + 8);

            for (int i = bullets.size() - 1; i >= 0; i--) {
                int[] b = bullets.get(i);
                boolean hit = false;
                for (int j = rocks.size() - 1; j >= 0; j--) {
                    int[] r = rocks.get(j);
                    if (Math.abs(b[0] - r[0]) <= r[2] && Math.abs(b[1] - r[1]) <= r[2]) {
                        bullets.remove(i);
                        rocks.remove(j);
                        score += 10;
                        hit = true;
                        break;
                    }
                }
                if (!hit) {
                    for (int j = ufos.size() - 1; j >= 0; j--) {
                        int[] u = ufos.get(j);
                        if (Math.abs(b[0] - u[0]) <= 3 && Math.abs(b[1] - u[1]) <= 3) {
                            bullets.remove(i);
                            ufos.remove(j);
                            score += 30;
                            break;
                        }
                    }
                }
            }
            for (int[] r : rocks) {
                if (Math.abs(r[0] - playerX) <= r[2] + 1 && Math.abs(r[1] - playerY) <= r[2] + 1) dead = true;
            }
            for (int[] u : ufos) {
                if (Math.abs(u[0] - playerX) <= 3 && Math.abs(u[1] - playerY) <= 3) dead = true;
            }
        }

        private boolean moveLeft, moveRight, fireHeld;
        private int gx, gy;   // 遊戲區左上（render 時更新，供滑鼠事件用）

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
            width = w - 8;
            height = h - 30;

            String status = dead ? "💀 爆炸了！" : "分數 " + score;
            Ui.textClipped(canvas, status, x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());
            if (clickOn(x + w - 40, y + 13, 38, 12)) reset();
            Ui.button(canvas, x + w - 40, y + 13, 38, 12, true, canvas.hovered(x + w - 40, y + 13, 38, 12));
            Ui.buttonLabel(canvas, x + w - 40, y + 13, 38, 12, "重開", true);

            gx = x + 4;
            gy = y + 26;
            long t = System.currentTimeMillis() / 100;
            for (int i = 0; i < 12; i++) {
                int sx = gx + (i * 53 + (int) (t * (i % 3 + 1))) % Math.max(1, width);
                int sy = gy + (i * 37 + (int) (t * (i % 2 + 1))) % Math.max(1, height);
                Ui.fill(g, sx, sy, 1, 1, 0xFF556070);
            }

            if (!dead && System.currentTimeMillis() - lastTick > 33) {
                tick();
                lastTick = System.currentTimeMillis();
            }
            if (dead) {
                Ui.drawCentered(canvas, "撞毀了！ 得分 " + score + "  按任意鍵重來", gx, gy + height / 2 - 8, width, 14, s.titleColor());
                if (moveLeft || moveRight || fireHeld) reset();
            }

            for (int[] b : bullets) Ui.fill(g, gx + b[0], gy + b[1], 1, 4, 0xFFFFF176);
            for (int[] r : rocks) Ui.circle(g, gx + r[0], gy + r[1], r[2], 0xFFA1887F);
            for (int[] u : ufos) {
                Ui.fill(g, gx + u[0] - 3, gy + u[1], 7, 3, 0xFF81C784);
                Ui.triangle(g, gx + u[0], gy + u[1] + 3, 3, 0xFF81C784);
            }
            Ui.triangle(g, gx + playerX, gy + playerY, 4, 0xFF4FC3F7);
            Ui.fill(g, gx + playerX - 2, gy + playerY + 3, 5, 2, 0xFFFF8A65);
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            switch (key) {
                case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> moveLeft = true;
                case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> moveRight = true;
                case GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> fireHeld = true;
                default -> {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean keyReleased(int key, int scan, int mods) {
            switch (key) {
                case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> moveLeft = false;
                case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> moveRight = false;
                case GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> fireHeld = false;
                default -> {
                    return false;
                }
            }
            return true;
        }

        // 滑鼠點擊遊戲區＝開火（手機環境主要靠點擊）
        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            super.mouseClicked(mouseX, mouseY, button);
            if (button == 0 && Ui.hit(gx, gy, width, height, (int) mouseX, (int) mouseY)) {
                fireHeld = true;
            }
            return true;
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            if (button == 0) fireHeld = false;
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
