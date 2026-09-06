package com.mcphoneultra.client.app;

import com.mcphoneultra.client.io.Store;
import com.mcphoneultra.client.ui.Ui;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** 翻牌記憶：8 對牌，記住位置湊對。商店可下載。 */
public final class MemoryApp extends BaseApp {

    public MemoryApp() {
        super("memory", false);
    }

    @Override
    protected IPhonePage createPage() {
        return new MemoryPage();
    }

    private static final class MemoryPage extends ClickablePage {
        private static final int PAIRS = 8;
        private final int[] cards = new int[PAIRS * 2];     // 牌面值 1..8
        private final boolean[] flipped = new boolean[PAIRS * 2];
        private final boolean[] matched = new boolean[PAIRS * 2];
        private int first = -1;
        private int moves;
        private boolean lock;
        private long lockUntil;
        private boolean won;
        private final Random rnd = new Random();
        private final Store store = Store.of("memory");

        private void reset() {
            List<Integer> vals = new ArrayList<>();
            for (int i = 0; i < PAIRS; i++) {
                vals.add(i + 1);
                vals.add(i + 1);
            }
            Collections.shuffle(vals, rnd);
            for (int i = 0; i < cards.length; i++) {
                cards[i] = vals.get(i);
                flipped[i] = false;
                matched[i] = false;
            }
            first = -1;
            moves = 0;
            lock = false;
            won = false;
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

            String status = won ? "🎉 全配對！ " + moves + " 步"
                    : "翻牌 " + moves + " 次  ·  找出 " + PAIRS + " 對";
            Ui.textClipped(canvas, status, x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());
            if (clickOn(x + w - 40, y + 13, 38, 12)) reset();
            Ui.button(canvas, x + w - 40, y + 13, 38, 12, true, canvas.hovered(x + w - 40, y + 13, 38, 12));
            Ui.buttonLabel(canvas, x + w - 40, y + 13, 38, 12, "重開", true);

            int cols = 4, rows = PAIRS * 2 / cols;
            int area = Math.min(w - 8, h - 40);
            int cw = area / cols;
            int ch = area / rows;
            int cell = Math.min(cw, ch);
            int gx = x + (w - cell * cols) / 2;
            int gy = y + 24;

            if (lock && System.currentTimeMillis() > lockUntil) {
                if (cards[first] != cards[second]) {
                    flipped[first] = false;
                    flipped[second] = false;
                } else {
                    matched[first] = true;
                    matched[second] = true;
                }
                first = -1;
                second = -1;
                lock = false;
            }

            for (int i = 0; i < cards.length; i++) {
                int r = i / cols, c = i % cols;
                int cx = gx + c * cell, cy = gy + r * cell;
                if (clickOn(cx, cy, cell, cell) && !lock && !won) {
                    if (!flipped[i] && !matched[i]) {
                        flipped[i] = true;
                        moves++;
                        if (first < 0) {
                            first = i;
                        } else if (second < 0 && first != i) {
                            second = i;
                            lock = true;
                            lockUntil = System.currentTimeMillis() + 700;
                        }
                    }
                }
                Ui.fill(g, cx, cy, cell, cell, flipped[i] || matched[i] ? 0xFF3A4553 : 0xFF26303D);
                Ui.border(canvas, cx, cy, cell, cell, 0xFF1A2028);
                if (flipped[i] || matched[i]) {
                    Ui.drawCentered(canvas, String.valueOf(cards[i]), cx, cy, cell, cell,
                            matched[i] ? 0xFFFFD54F : 0xFF81C784);
                }
            }

            won = true;
            for (int i = 0; i < matched.length; i++) if (!matched[i]) won = false;
            if (won) {
                int best = store.getInt("best", 0);
                if (best == 0 || moves < best) store.setInt("best", moves);
            }
        }

        private int second = -1;

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            return true;
        }
    }
}
