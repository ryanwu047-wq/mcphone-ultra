package com.mcphoneultra.client.app;

import com.mcphoneultra.client.ui.Ui;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.core.client.PhoneScreenOpener;
import com.november.mcphone.core.client.PhoneTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 📱 平板設置：切換平板橫向／直向（僅在平板裡有用，手機上提示）。
 */
public final class TabletApp extends BaseApp {

    public TabletApp() {
        super("tablet", false);
    }

    @Override
    protected IPhonePage createPage() {
        return new TabletPage();
    }

    private static final class TabletPage extends ClickablePage {
        private String toast = "";
        private long toastUntil;

        private void toast(String s) {
            toast = s;
            toastUntil = System.currentTimeMillis() + 2000;
        }

        @Override
        public void render(com.november.mcphone.api.client.ui.PhoneCanvas c) {
            int x = c.x(), y = c.y(), w = c.width(), h = c.height();
            var s = c.style();
            GuiGraphics g = c.graphics();
            Ui.fill(g, x, y, w, h, s.screenBackground());
            Ui.textClipped(c, "📱 平板設置", x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            boolean landscape = PhoneTheme.PHONE_WIDTH > PhoneTheme.PHONE_HEIGHT;
            Ui.drawCentered(c, "目前：" + (landscape ? "橫向 320×190" : "直向 190×300"),
                    x, y + 30, w, 12, s.titleColor());
            Ui.drawCentered(c, "平板解析度比手機（120×200）大，所有 App 自動放大",
                    x, y + 44, w, 12, s.subtleColor());

            if (clickOn(x + w / 2 - 40, y + 70, 80, 16)) {
                var mc = Minecraft.getInstance();
                if (mc.player == null) return;
                PhoneScreenOpener.openTablet(mc.player, !landscape);
            }
            Ui.button(c, x + w / 2 - 40, y + 70, 80, 16, true, c.hovered(x + w / 2 - 40, y + 70, 80, 16));
            Ui.buttonLabel(c, x + w / 2 - 40, y + 70, 80, 16,
                    landscape ? "切到直向" : "切到橫向", true);

            if (clickOn(x + w / 2 - 60, y + 100, 120, 16)) {
                toast("合成：手機＋下界合金錠×4＋鑽石塊×2＋鑽石×4");
            }
            Ui.button(c, x + w / 2 - 60, y + 100, 120, 16, true, c.hovered(x + w / 2 - 60, y + 100, 120, 16));
            Ui.buttonLabel(c, x + w / 2 - 60, y + 100, 120, 16, "平板配方", true);

            if (System.currentTimeMillis() < toastUntil && !toast.isEmpty()) {
                Ui.drawCentered(c, toast, x, y + h - 14, w, 12, s.titleColor());
            }
        }
    }
}
