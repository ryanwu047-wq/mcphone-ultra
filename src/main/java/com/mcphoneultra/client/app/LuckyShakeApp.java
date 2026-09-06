package com.mcphoneultra.client.app;

import com.mcphoneultra.client.ui.Ui;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.Random;

/**
 * 忘憂鈴（原名「摇一摇广告」，已改名）：搖一搖鈴鐺，隨機得到一句小確幸。
 * 純娛樂小應用，不含任何廣告。
 */
public final class LuckyShakeApp extends BaseApp {

    public LuckyShakeApp() {
        super("lucky", false);
    }

    @Override
    protected IPhonePage createPage() {
        return new LuckyShakePage();
    }

    private static final class LuckyShakePage extends ClickablePage {
        private static final String[] MESSAGES = {
                "今天也是閃閃發光的一天 ✨",
                "記得喝水，也記得笑一笑 😊",
                "你比你想的更厲害 💪",
                "深呼吸，世界沒有那麼糟 🌿",
                "幸運正在路上，耐心等一下 🍀",
                "睡前別想太多，明天再說 🌙",
                "小小的進步也是進步 🌱",
                "你值得被好好對待 🌸",
                "風會記得你的努力 🌬",
                "星光不問趕路人 🌟",
        };
        private final Random rnd = new Random();
        private String message = "點一下鈴鐺，搖出今日小確幸";
        private long messageUntil;
        private int shakeOffset;
        private long shakeUntil;

        @Override
        public void render(PhoneCanvas canvas) {
            int x = canvas.x(), y = canvas.y(), w = canvas.width(), h = canvas.height();
            var s = canvas.style();
            GuiGraphics g = canvas.graphics();
            Ui.fill(g, x, y, w, h, s.screenBackground());

            Ui.textClipped(canvas, "忘憂鈴", x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            long now = System.currentTimeMillis();
            if (now > messageUntil) message = "";
            int cx = x + w / 2;
            int cy = y + h / 2 - 10;

            int shake = 0;
            if (now < shakeUntil) {
                shake = rnd.nextInt(7) - 3;
            }
            if (clickOn(cx - 30, cy - 30, 60, 60)) {
                message = MESSAGES[rnd.nextInt(MESSAGES.length)];
                messageUntil = now + 4000;
                shakeUntil = now + 500;
            }

            // 鈴鐺：圓身 + 開口 + 頂扣
            Ui.circle(g, cx + shake, cy, 22, 0xFFFFD54F);
            Ui.fill(g, cx - 12 + shake, cy + 12, 24, 8, 0xFFFFB300);
            Ui.circle(g, cx + shake, cy - 24, 5, 0xFFFFB300);
            Ui.fill(g, cx - 10 + shake, cy - 21, 20, 4, 0xFFFFB300);
            Ui.fill(g, cx - 3 + shake, cy + 12, 6, 12, 0xFFE65100);

            if (!message.isEmpty()) {
                int tw = canvas.font().width(message) + 10;
                int tx = x + (w - tw) / 2;
                int ty = cy + 44;
                Ui.fill(g, tx, ty, tw, 14, 0xFF202830);
                Ui.textClipped(canvas, message, tx + 5, ty + 2, s.titleColor(), x, ty, w, 16);
            } else {
                Ui.text(canvas, "（按空白鍵也可以搖）", cx - 60, cy + 48, s.subtleColor());
            }
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_ENTER) {
                shakeUntil = System.currentTimeMillis() + 500;
                message = MESSAGES[rnd.nextInt(MESSAGES.length)];
                messageUntil = System.currentTimeMillis() + 4000;
                return true;
            }
            return false;
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            return true;
        }
    }
}
