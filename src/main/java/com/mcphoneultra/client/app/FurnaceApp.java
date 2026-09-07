package com.mcphoneultra.client.app;

import com.mcphoneultra.client.net.CloudPackets;
import com.mcphoneultra.client.ui.Ui;
import com.november.mcphone.api.client.ui.IPhonePage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 🔥 隨身熔爐（手機內 UI）：放副手物品進輸入/燃料，服務端每 tick 燒煉。
 * VIP 加速：VIP 2×、SVIP 4×；1 煤仍燒 8 個物品，加速只縮短時間。
 */
public final class FurnaceApp extends BaseApp {

    public FurnaceApp() {
        super("furnace", true);
    }

    @Override
    protected IPhonePage createPage() {
        return new FurnacePage();
    }

    public static void onState(CloudPackets.FurnaceStateS2C pkt) {
        FurnacePage.receive(pkt);
    }

    private static final class FurnacePage extends ClickablePage {
        private static volatile ItemStack in = ItemStack.EMPTY;
        private static volatile ItemStack fuel = ItemStack.EMPTY;
        private static volatile ItemStack out = ItemStack.EMPTY;
        private static volatile int progress;
        private static volatile int burn;
        private static volatile int speed = 1;
        private String toast = "";
        private long toastUntil;

        static void receive(CloudPackets.FurnaceStateS2C pkt) {
            in = pkt.input();
            fuel = pkt.fuel();
            out = pkt.output();
            progress = pkt.progress();
            burn = pkt.burnTicks();
            speed = pkt.speed();
        }

        private void toast(String s) {
            toast = s;
            toastUntil = System.currentTimeMillis() + 2000;
        }

        @Override
        public void onOpen() {
            PacketDistributor.sendToServer(new CloudPackets.FurnaceOpenC2S());
        }

        @Override
        public void onClose() {
            PacketDistributor.sendToServer(new CloudPackets.FurnaceCloseC2S());
            // 清空本地殘留，避免關機畫面殘影造成「複製一份」錯覺
            in = ItemStack.EMPTY;
            fuel = ItemStack.EMPTY;
            out = ItemStack.EMPTY;
            progress = 0;
            burn = 0;
        }

        @Override
        public void render(com.november.mcphone.api.client.ui.PhoneCanvas c) {
            int x = c.x(), y = c.y(), w = c.width(), h = c.height();
            var s = c.style();
            GuiGraphics g = c.graphics();
            Ui.fill(g, x, y, w, h, s.screenBackground());

            Ui.textClipped(c, "🔥 隨身熔爐", x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.textClipped(c, "加速 " + speed + "×", x + w - 50, y + 2, s.subtleColor(), x, y, 48, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            int cell = 34;
            int gy = y + 38;
            int centerY = gy + cell / 2 - 5;

            // 輸入槽（左）／燃料槽（右）
            drawSlot(c, g, s, x + 6, gy, cell, in, "輸入");
            drawSlot(c, g, s, x + w - 40, gy, cell, fuel, "燃料");
            // 輸出槽（燃料下方）
            drawSlot(c, g, s, x + w - 40, gy + 60, cell, out, "輸出");

            // 火焰（燃料上方）
            int fl = burn > 0 ? Math.max(3, 20 * Math.min(burn, 1600) / 1600) : 0;
            Ui.fill(g, x + w - 32, gy - 24, 4 + fl / 2, 4, 0xFFE07B00);
            Ui.border(c, x + w - 33, gy - 25, 22, 6, s.buttonDisabledColor());

            // 進度條（輸入→燃料之間）
            int px = x + 44;
            int pw = w - 88;
            Ui.fill(g, px, centerY, pw, 8, 0xFF101418);
            Ui.fill(g, px, centerY, (int) (pw * progress / 200.0), 8, 0xFF5AC8FA);
            Ui.border(c, px, centerY, pw, 8, s.buttonDisabledColor());

            // 按鈕：三顆 38 寬並排，120 寬剛好放下
            int by = y + h - 34;
            if (clickOn(x + 4, by, 38, 12)) {
                PacketDistributor.sendToServer(new CloudPackets.FurnacePutInputC2S());
            }
            Ui.button(c, x + 4, by, 38, 12, true, c.hovered(x + 4, by, 38, 12));
            Ui.buttonLabel(c, x + 4, by, 38, 12, "放輸入", true);
            if (clickOn(x + 45, by, 38, 12)) {
                PacketDistributor.sendToServer(new CloudPackets.FurnacePutFuelC2S());
            }
            Ui.button(c, x + 45, by, 38, 12, true, c.hovered(x + 45, by, 38, 12));
            Ui.buttonLabel(c, x + 45, by, 38, 12, "放燃料", true);
            if (clickOn(x + 86, by, 38, 12)) {
                PacketDistributor.sendToServer(new CloudPackets.FurnaceTakeOutputC2S());
            }
            Ui.button(c, x + 86, by, 38, 12, true, c.hovered(x + 86, by, 38, 12));
            Ui.buttonLabel(c, x + 86, by, 38, 12, "取輸出", true);
            Ui.drawCentered(c, "物品放副手後點對應按鈕存入", x, y + h - 18, w, 12, s.subtleColor());

            if (System.currentTimeMillis() < toastUntil && !toast.isEmpty()) {
                Ui.drawCentered(c, toast, x, y + h - 14, w, 12, s.titleColor());
            }
        }

        private static void drawSlot(com.november.mcphone.api.client.ui.PhoneCanvas c,
                                     GuiGraphics g, com.november.mcphone.api.client.ui.PhoneStyle st,
                                     int sx, int sy, int size, ItemStack stack, String label) {
            Ui.fill(g, sx, sy, size, size, 0xFF1A1F26);
            Ui.border(c, sx, sy, size, size, st.buttonDisabledColor());
            Ui.text(c, label, sx + 3, sy + size + 2, st.subtleColor());
            if (stack != null && !stack.isEmpty()) {
                g.renderItem(stack, sx + 6, sy + 6);
                g.renderItemDecorations(c.font(), stack, sx + 6, sy + 6);
            }
        }
    }
}
