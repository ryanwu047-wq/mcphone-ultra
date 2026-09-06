package com.mcphoneultra.client.app;

import com.mcphoneultra.client.ui.Ui;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.gui.GuiGraphics;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 晨光卡片（原名「开屏广告」，已改名）：每次打開手機都先看到一張問候卡。
 * 顯示時間與一句話，點卡片換主題色。不含任何廣告。
 */
public final class SplashCardApp extends BaseApp {

    public SplashCardApp() {
        super("splash", false);
    }

    @Override
    protected IPhonePage createPage() {
        return new SplashCardPage();
    }

    private static final class SplashCardPage extends ClickablePage {
        private static final String[] GREETINGS = {
                "早安，礦工！新的一天從挖礦開始 ⛏",
                "午安，冒險者！記得吃午餐 🍖",
                "晚安，勇士！今晚的星空很亮 🌙",
                "祝你有個好心情，不管幾點 💛",
                "世界很大，先去撿顆鑽石 💎",
        };
        private static final int[] THEMES = {
                0xFF4FC3F7, 0xFF81C784, 0xFFFF8A65, 0xFFBA68C8, 0xFFFFD54F,
        };
        private int theme = 0;

        private static String greeting() {
            int hour = LocalDateTime.now().getHour();
            if (hour < 6) return GREETINGS[3];
            if (hour < 12) return GREETINGS[0];
            if (hour < 18) return GREETINGS[1];
            return GREETINGS[2];
        }

        @Override
        public void render(PhoneCanvas canvas) {
            int x = canvas.x(), y = canvas.y(), w = canvas.width(), h = canvas.height();
            var s = canvas.style();
            GuiGraphics g = canvas.graphics();
            Ui.fill(g, x, y, w, h, s.screenBackground());

            int accent = THEMES[theme % THEMES.length];
            int pad = 8;
            Ui.fill(g, x + pad, y + pad, w - pad * 2, h - pad * 2, 0xFF202830);
            Ui.border(canvas, x + pad, y + pad, w - pad * 2, h - pad * 2, accent);

            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
            String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd  EEEE"));

            Ui.drawCentered(canvas, time, x, y + 40, w, 40, accent);
            Ui.drawCentered(canvas, date, x, y + 82, w, 12, s.subtleColor());
            Ui.hline(g, x + 30, x + w - 30, y + 102, s.buttonDisabledColor());

            String msg = greeting();
            Ui.drawCentered(canvas, msg, x, y + 116, w, 14, s.titleColor());

            if (clickOn(x, y, w, h)) {
                theme++;
            }
            Ui.drawCentered(canvas, "點卡片換主題", x, y + h - 40, w, 10, s.subtleColor());
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            return true;
        }
    }
}
