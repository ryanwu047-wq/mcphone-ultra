package com.mcphoneultra.client.app;

import com.mcphoneultra.client.io.Paths;
import com.mcphoneultra.client.net.VillagerTradePacket;
import com.mcphoneultra.client.ui.Scroller;
import com.mcphoneultra.client.ui.Ui;
import com.mcphoneultra.client.villager.VillagerData;
import com.november.mcphone.api.client.ui.IPhonePage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/** 村民聯絡人：綁定（蹲下＋右鍵）過的村民，聊天式列表，可直接遠程交易。 */
public final class VillagerTradeApp extends BaseApp {

    public VillagerTradeApp() {
        super("villager", true);
    }

    @Override
    protected IPhonePage createPage() {
        return new VillagerPage();
    }

    private static final class VillagerPage extends ClickablePage {
        private final Scroller scroller = new Scroller();
        private List<VillagerData.Bound> list = List.of();
        private String toast = "";
        private long toastUntil;

        private void toast(String s) {
            toast = s;
            toastUntil = System.currentTimeMillis() + 2000;
        }

        private void reload() {
            list = VillagerData.load();
        }

        @Override
        public void onOpen() {
            reload();
        }

        @Override
        public void render(com.november.mcphone.api.client.ui.PhoneCanvas c) {
            int x = c.x(), y = c.y(), w = c.width(), h = c.height();
            var s = c.style();
            GuiGraphics g = c.graphics();
            Ui.fill(g, x, y, w, h, s.screenBackground());

            Ui.textClipped(c, "📇 村民聯絡人", x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            if (list.isEmpty()) {
                Ui.drawCentered(c, "還沒有綁定村民", x, y + h / 2 - 14, w, 12, s.bodyColor());
                Ui.drawCentered(c, "蹲下＋右鍵村民 ＝ 綁定聯絡人", x, y + h / 2, w, 12, s.subtleColor());
                Ui.drawCentered(c, "綁定後可遠程交易、正常升級", x, y + h / 2 + 14, w, 12, s.subtleColor());
                return;
            }

            int rowH = 32;
            int listY = y + 13;
            int listH = h - 13 - 18;
            scroller.clamp(list.size() * rowH, listH);
            int off = (int) scroller.offset();
            int bx = x + w - 58;   // 右側按鈕列
            int textW = w - 64;    // 文字區寬（留按鈕＋邊距），手機 120 寬下不溢出
            for (int i = 0; i < list.size(); i++) {
                VillagerData.Bound b = list.get(i);
                int ry = listY + i * rowH - off;
                if (ry + rowH < listY || ry > listY + listH) continue;

                Ui.fill(g, x + 2, ry + 1, w - 4, rowH - 2, s.pressedOverlay());
                Ui.hline(g, x + 2, x + w - 2, ry + rowH - 1, s.buttonDisabledColor());

                // 名字＋等級
                Ui.textClipped(c, "👤 " + b.name() + "  ★" + b.level(),
                        x + 5, ry + 2, s.titleColor(), x, ry, textW, rowH);
                // 職業
                Ui.textClipped(c, profession(b), x + 5, ry + 12, s.bodyColor(), x, ry, textW, rowH);
                // 座標＋附近距離（同一行，避免重疊）
                int dist = distTo(b);
                String posLine = "📍 " + b.pos() + (dist >= 0 ? "  附近" + dist + "m" : "");
                Ui.textClipped(c, posLine, x + 5, ry + 22,
                        dist >= 0 ? s.accentColor() : s.subtleColor(), x, ry, textW, rowH);

                // 按鈕：交易 / 更新 / 解綁
                if (clickOn(bx, ry + 1, 54, 9)) trade(b);
                Ui.button(c, bx, ry + 1, 54, 9, true, c.hovered(bx, ry + 1, 54, 9));
                Ui.buttonLabel(c, bx, ry + 1, 54, 9, "💼 交易", true);
                if (clickOn(bx, ry + 12, 26, 8)) refresh(b);
                Ui.button(c, bx, ry + 12, 26, 8, true, c.hovered(bx, ry + 12, 26, 8));
                Ui.buttonLabel(c, bx, ry + 12, 26, 8, "🔄", true);
                if (clickOn(bx + 28, ry + 12, 26, 8)) {
                    VillagerData.unbind(b.uuid());
                    toast("已解綁");
                    reload();
                }
                Ui.button(c, bx + 28, ry + 12, 26, 8, true, c.hovered(bx + 28, ry + 12, 26, 8));
                Ui.buttonLabel(c, bx + 28, ry + 12, 26, 8, "🗑", true);
            }

            if (System.currentTimeMillis() < toastUntil && !toast.isEmpty()) {
                Ui.drawCentered(c, toast, x, y + h - 14, w, 12, s.titleColor());
            }
        }

        private void trade(VillagerData.Bound b) {
            PacketDistributor.sendToServer(new VillagerTradePacket(b.uuid()));
            toast("已請求交易…");
        }

        private void refresh(VillagerData.Bound b) {
            var mc = Minecraft.getInstance();
            if (mc.level == null) {
                toast("不在世界裡");
                return;
            }
            if (findVillager(b.uuid()) != null) {
                VillagerData.update(findVillager(b.uuid()));
                toast("已更新");
                reload();
            } else {
                toast("村民不在附近，保留舊資料");
            }
        }

        private static int distTo(VillagerData.Bound b) {
            var mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) return -1;
            Villager v = findVillager(b.uuid());
            if (v != null) {
                return (int) Math.round(mc.player.distanceTo(v));
            }
            return -1;
        }

        /** 在客戶端已載入的實體中按 UUID 找村民（遠程村民不會載入，回 null） */
        private static Villager findVillager(java.util.UUID uuid) {
            var mc = Minecraft.getInstance();
            if (mc.level == null) return null;
            for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
                if (e.getUUID().equals(uuid) && e instanceof Villager v) return v;
            }
            return null;
        }

        private static String profession(VillagerData.Bound b) {
            return Component.translatable("entity.minecraft.villager." + b.profession().toLowerCase()).getString();
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            scroller.onWheel(amount, list.size() * 32, 130);
            return true;
        }
    }
}
