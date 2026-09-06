package com.mcphoneultra.client.app;

import com.mcphoneultra.client.net.PhoneCallPacket;
import com.mcphoneultra.client.ui.Ui;
import com.mcphoneultra.server.VoiceCall;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import com.november.mcphone.api.client.ui.PhoneStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * ☎ 電話：撥號＝建立 Simple Voice Chat 語音群組（對方按 U 加入即通話），
 * 掛斷＝移除群組。沒裝語音 mod 時顯示提示。
 */
public final class PhoneApp extends BaseApp {

    public PhoneApp() {
        super("phone", false);
    }

    @Override
    protected IPhonePage createPage() {
        return new PhonePage();
    }

    private static final class PhonePage extends ClickablePage {
        private String target = "";
        private boolean typing;
        private String toast = "";
        private long toastUntil;

        private void toast(String s) {
            toast = s;
            toastUntil = System.currentTimeMillis() + 3000;
        }

        @Override
        public void render(PhoneCanvas c) {
            int x = c.x(), y = c.y(), w = c.width(), h = c.height();
            var s = c.style();
            GuiGraphics g = c.graphics();
            Ui.fill(g, x, y, w, h, s.screenBackground());
            Ui.textClipped(c, "☎ 電話", x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            boolean svc = VoiceCall.svcAvailable();
            Ui.drawCentered(c, svc ? "語音已連線（Simple Voice Chat）" : "未安裝 Simple Voice Chat",
                    x, y + 16, w, 12, svc ? s.accentColor() : s.buttonDisabledColor());
            Ui.drawCentered(c, "撥號＝建立語音群組，對方按 U 加入即通話",
                    x, y + 28, w, 12, s.subtleColor());

            Ui.text(c, "對方玩家名", x + 4, y + 44, s.subtleColor());
            if (clickOn(x + 4, y + 54, w - 8, 12)) typing = true;
            Ui.fill(g, x + 4, y + 54, x + w - 4, y + 66, 0xFF000000);
            Ui.textClipped(c, target.isEmpty() ? "（點這裡輸入）" : target,
                    x + 6, y + 55, s.accentColor(), x, y + 54, w - 8, 12);

            if (clickOn(x + w / 2 - 46, y + 78, 90, 16)) {
                if (!svc) {
                    toast("先安裝 Simple Voice Chat 才能通話");
                } else if (target.trim().isEmpty()) {
                    toast("輸入對方玩家名（可不填，播給全服）");
                } else {
                    PacketDistributor.sendToServer(new PhoneCallPacket.PhoneCallC2S(0, target.trim()));
                    toast("已撥號");
                }
            }
            Ui.button(c, x + w / 2 - 46, y + 78, 90, 16, true, c.hovered(x + w / 2 - 46, y + 78, 90, 16));
            Ui.buttonLabel(c, x + w / 2 - 46, y + 78, 90, 16, "📞 撥號", true);

            if (clickOn(x + w / 2 - 46, y + 100, 90, 16)) {
                PacketDistributor.sendToServer(new PhoneCallPacket.PhoneCallC2S(1, ""));
                toast("已掛斷");
            }
            Ui.button(c, x + w / 2 - 46, y + 100, 90, 16, true, c.hovered(x + w / 2 - 46, y + 100, 90, 16));
            Ui.buttonLabel(c, x + w / 2 - 46, y + 100, 90, 16, "📵 掛斷", true);

            Ui.drawCentered(c, "玩法：A 撥號 → B 按 U 加入群組 → 語音聊天",
                    x, y + 128, w, 12, s.subtleColor());
            Ui.drawCentered(c, "掛斷後群組消失，全部人退出",
                    x, y + 140, w, 12, s.subtleColor());

            if (System.currentTimeMillis() < toastUntil && !toast.isEmpty()) {
                Ui.drawCentered(c, toast, x, y + h - 14, w, 12, s.titleColor());
            }
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (typing && key == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE && !target.isEmpty()) {
                target = target.substring(0, target.length() - 1);
                return true;
            }
            return typing;
        }

        @Override
        public boolean charTyped(char ch, int mods) {
            if (typing) {
                if ((Character.isLetterOrDigit(ch) || ch == '_' || ch == '-') && target.length() < 16) {
                    target += ch;
                }
                return true;
            }
            return false;
        }

        @Override
        public boolean onBack() {
            if (typing) {
                typing = false;
                return true;
            }
            return false;
        }

        @Override
        public boolean capturesKeyboard() {
            return typing;
        }
    }
}
