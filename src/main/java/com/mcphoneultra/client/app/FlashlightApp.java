package com.mcphoneultra.client.app;

import com.mcphoneultra.client.net.CloudPackets;
import com.mcphoneultra.client.ui.Ui;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import com.november.mcphone.api.client.ui.PhoneStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 🔦 手電筒：開啟後在玩家頭頂放 15 級隱形光源，跟著玩家走。
 * 開關狀態本地記錄，服務端只管光源。
 */
public final class FlashlightApp extends BaseApp {

    public FlashlightApp() {
        super("flashlight", false);
    }

    @Override
    protected IPhonePage createPage() {
        return new FlashlightPage();
    }

    private static final class FlashlightPage extends ClickablePage {
        private static boolean on;

        @Override
        public void render(PhoneCanvas c) {
            int x = c.x(), y = c.y(), w = c.width(), h = c.height();
            var s = c.style();
            GuiGraphics g = c.graphics();
            Ui.fill(g, x, y, w, h, s.screenBackground());
            Ui.textClipped(c, "🔦 手電筒", x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            Ui.drawCentered(c, on ? "已開啟（15 級光源）" : "已關閉", x, y + 20, w, 12,
                    on ? s.accentColor() : s.buttonDisabledColor());
            Ui.drawCentered(c, "開啟後光源會跟著你移動", x, y + 34, w, 12, s.subtleColor());

            if (clickOn(x + w / 2 - 40, y + 56, 80, 16)) {
                on = !on;
                PacketDistributor.sendToServer(new CloudPackets.FlashlightToggleC2S());
            }
            Ui.button(c, x + w / 2 - 40, y + 56, 80, 16, true,
                    c.hovered(x + w / 2 - 40, y + 56, 80, 16));
            Ui.buttonLabel(c, x + w / 2 - 40, y + 56, 80, 16, on ? "關閉手電筒" : "開啟手電筒", true);

            Ui.drawCentered(c, "不需要手持任何物品，純光源照明", x, y + 86, w, 12, s.subtleColor());
        }

        @Override
        public boolean capturesKeyboard() {
            return false;
        }
    }
}
