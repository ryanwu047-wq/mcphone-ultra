package com.mcphoneultra.client.app;

import com.mcphoneultra.client.ui.Ui;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.fml.ModList;

/**
 * VR 連動：偵測 VR 模組、顯示玩家頭部視角，並提供「雙目預覽」模式——
 * 把手機畫面左右各畫一遍（模擬 VR 眼鏡的雙眼視野），可調瞳距。
 */
public final class VrApp extends BaseApp {

    public VrApp() {
        super("vr", true);
    }

    @Override
    protected IPhonePage createPage() {
        return new VrPage();
    }

    private static final class VrPage extends ClickablePage {
        private boolean dualMode;
        private int ipd = 20;   // 瞳距（像素偏移）

        @Override
        public void render(PhoneCanvas c) {
            int x = c.x(), y = c.y(), w = c.width(), h = c.height();
            var s = c.style();
            GuiGraphics g = c.graphics();
            Ui.fill(g, x, y, w, h, s.screenBackground());

            Ui.textClipped(c, "🥽 VR 連動", x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            if (clickOn(x + w - 58, y + 13, 56, 12)) dualMode = !dualMode;
            Ui.button(c, x + w - 58, y + 13, 56, 12, true, c.hovered(x + w - 58, y + 13, 56, 12));
            Ui.buttonLabel(c, x + w - 58, y + 13, 56, 12, dualMode ? "單畫面" : "雙目預覽", true);

            Minecraft mc = Minecraft.getInstance();
            boolean vr = ModList.get().isLoaded("vivecraft");
            boolean oculus = ModList.get().isLoaded("oculus");

            if (dualMode) {
                renderDual(c, x, y, w, h, mc);
                return;
            }

            int cardY = y + 30;
            Ui.fill(g, x + 4, cardY, w - 8, 56, 0xFF202830);
            Ui.border(c, x + 4, cardY, w - 8, 56, s.accentColor());
            Ui.text(c, "VR 模組：" + (vr ? "✓ Vivecraft 已安裝" : "✗ 未偵測到 Vivecraft"), x + 8, cardY + 4, vr ? 0xFF81C784 : 0xFFFF8A65);
            Ui.text(c, "光影模組：" + (oculus ? "✓ Oculus 已安裝" : "— 非必要"), x + 8, cardY + 18, s.subtleColor());
            Ui.text(c, "雙目預覽：把手機畫面當 VR 眼鏡，左右各畫一次。", x + 8, cardY + 32, s.subtleColor());

            if (mc.player != null) {
                float yaw = mc.player.getYRot();
                float pitch = mc.player.getXRot();
                String dim = mc.level == null ? "?" : mc.level.dimension().location().toString();
                String line1 = "玩家視角  yaw " + String.format("%.1f", yaw) + "°  pitch " + String.format("%.1f", pitch) + "°";
                String line2 = "所在維度 " + dim + "  ·  位置 "
                        + String.format("%.1f, %.1f, %.1f", mc.player.getX(), mc.player.getY(), mc.player.getZ());
                Ui.fill(g, x + 4, cardY + 62, w - 8, 40, 0xFF202830);
                Ui.border(c, x + 4, cardY + 62, w - 8, 40, s.buttonDisabledColor());
                Ui.textClipped(c, "👤 " + line1, x + 8, cardY + 68, s.bodyColor(), x, cardY, w - 8, 40);
                Ui.textClipped(c, line2, x + 8, cardY + 82, s.subtleColor(), x, cardY, w - 8, 40);
            } else {
                Ui.text(c, "（進到世界裡才能看到玩家視角）", x + 8, cardY + 72, s.subtleColor());
            }

            Ui.text(c, "瞳距調整（雙目預覽用）", x + 8, cardY + 112, s.subtleColor());
            int barX = x + 8, barY = cardY + 124, barW = w - 16;
            Ui.fill(g, barX, barY, barW, 6, s.buttonDisabledColor());
            Ui.fill(g, barX, barY, barW * ipd / 40, 6, s.accentColor());
            if (clickOn(barX, barY, barW, 6)) {
                ipd = Math.max(6, Math.min(40, (c.mouseX() - barX) * 40 / barW));
            }
        }

        private void renderDual(PhoneCanvas c, int x, int y, int w, int h, Minecraft mc) {
            var s = c.style();
            GuiGraphics g = c.graphics();
            Ui.fill(g, x, y, w, h, 0xFF000000);
            int half = w / 2;
            // 左右兩眼各畫一個「畫面」，中間用瞳距偏移
            for (int eye = 0; eye < 2; eye++) {
                int ex = x + eye * half;
                int off = eye == 0 ? -ipd / 2 : ipd / 2;
                Ui.fill(g, ex + 2, y + 2, half - 4, h - 4, 0xFF1E242E);
                Ui.border(c, ex + 2, y + 2, half - 4, h - 4, 0xFF3A4553);
                // 中央十字（VR 眼鏡的注視點）
                Ui.fill(g, ex + half / 2 - 1 + off, y + h / 2 - 8, 2, 16, 0x88FFFFFF);
                Ui.fill(g, ex + half / 2 - 8 + off, y + h / 2 - 1, 16, 2, 0x88FFFFFF);

                String label = eye == 0 ? "左眼" : "右眼";
                Ui.text(c, label, ex + 6, y + 6, 0xFF81C784);
                Ui.textClipped(c, "這是 VR 眼鏡裡看到的畫面", ex + 6, y + 24, s.subtleColor(), ex, y, half, h);
                if (mc.player != null) {
                    Ui.textClipped(c, "yaw " + String.format("%.1f", mc.player.getYRot()) + "°",
                            ex + 6, y + 38, s.subtleColor(), ex, y, half, h);
                }
                // 模擬「世界」的簡單景物
                for (int i = 0; i < 8; i++) {
                    int px = ex + 10 + i * 18;
                    Ui.fill(g, px, y + h - 40, 8, 30, 0xFF4CAF50);
                    Ui.triangle(g, px + 4, y + h - 48, 8, 0xFF388E3C);
                }
                Ui.text(c, "用滑鼠滾輪在你面前走動吧", ex + 6, y + h - 60, 0xFF556070);
            }
            Ui.vline(g, x + half, y, y + h, 0xFF303A46);
            Ui.text(c, "瞳距 " + ipd + "px  ·  返回後可調整", x + 6, y + h - 12, 0xFF556070);
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            return true;
        }
    }
}
