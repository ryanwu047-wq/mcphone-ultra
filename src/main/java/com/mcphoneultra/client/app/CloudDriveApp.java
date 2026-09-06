package com.mcphoneultra.client.app;

import com.mcphoneultra.client.net.CloudPackets;
import com.mcphoneultra.client.ui.Scroller;
import com.mcphoneultra.client.ui.Ui;
import com.mcphoneultra.server.CloudTier;
import com.november.mcphone.api.client.ui.IPhonePage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ☁️ 網盤：比末影箱大得多的空間，商店下載。
 * 等級＝大箱子數 × 堆疊上限（見 CloudTier）；無會員存取限速每秒 1 次。
 * 放入：物品放副手 → 點格子 →「放入」；取出：點格子 →「取出」。
 */
public final class CloudDriveApp extends BaseApp {

    public CloudDriveApp() {
        super("cloud", true);
    }

    @Override
    protected IPhonePage createPage() {
        return new CloudPage();
    }

    /** 由 NetworkHandler 在接收線程回調（enqueueWork 到主線程後） */
    public static void onSnapshot(CloudPackets.CloudSnapshotS2C pkt) {
        CloudPage.receive(pkt);
    }

    private static final class CloudPage extends ClickablePage {
        private static volatile int snapTier;
        private static volatile long snapRemaining;
        private static volatile int snapPage;
        /** 快照格資料：主線程寫入／讀取，AtomicReference 保證陣列引用原子交換 */
        private static final AtomicReference<ItemStack[]> SNAP_SLOTS = new AtomicReference<>(new ItemStack[0]);

        private final Scroller upScroller = new Scroller();
        private int selected = -1;          // 全網盤索引
        private boolean upgrading;
        private static volatile boolean purchasePending; // 升級請求已送出，等快照回應（防連點）
        private String toast = "";
        private long toastUntil;

        static void receive(CloudPackets.CloudSnapshotS2C pkt) {
            snapTier = pkt.tier();
            snapRemaining = pkt.remainingMs();
            snapPage = pkt.page();
            ItemStack[] arr = new ItemStack[pkt.slots().size()];
            for (int i = 0; i < arr.length; i++) arr[i] = pkt.slots().get(i);
            SNAP_SLOTS.set(arr);
            purchasePending = false; // 服務端已回應（快照），解除防連點
        }

        private void toast(String s) {
            toast = s;
            toastUntil = System.currentTimeMillis() + 2000;
        }

        private CloudTier tier() {
            return CloudTier.values()[Math.max(0, Math.min(snapTier, CloudTier.values().length - 1))];
        }

        @Override
        public void onOpen() {
            PacketDistributor.sendToServer(new CloudPackets.CloudOpenC2S());
        }

        @Override
        public void render(com.november.mcphone.api.client.ui.PhoneCanvas c) {
            int x = c.x(), y = c.y(), w = c.width(), h = c.height();
            var s = c.style();
            GuiGraphics g = c.graphics();
            Ui.fill(g, x, y, w, h, s.screenBackground());

            if (upgrading) {
                renderUpgrade(c);
                return;
            }

            CloudTier tier = tier();
            // 標題列
            Ui.textClipped(c, "☁️ 網盤 " + tier.label + "  " + timeLeft(snapRemaining),
                    x + 3, y + 2, s.titleColor(), x, y, w - 38, 12);
            if (clickOn(x + w - 34, y + 1, 31, 10)) {
                upgrading = true;
            }
            Ui.button(c, x + w - 34, y + 1, 31, 10, true, c.hovered(x + w - 34, y + 1, 31, 10));
            Ui.buttonLabel(c, x + w - 34, y + 1, 31, 10, "升級", true);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            // 翻頁
            int pages = Math.max(1, (tier.slots() + 29) / 30);
            int py = y + 12;
            if (clickOn(x + 6, py, 30, 10) && snapPage > 0) {
                PacketDistributor.sendToServer(new CloudPackets.CloudPageC2S(snapPage - 1));
            }
            Ui.button(c, x + 6, py, 30, 10, snapPage > 0, c.hovered(x + 6, py, 30, 10));
            Ui.buttonLabel(c, x + 6, py, 30, 10, "◀", snapPage > 0);
            Ui.drawCentered(c, "第 " + (snapPage + 1) + "/" + pages + " 頁", x + 38, py, w - 76, 10, s.bodyColor());
            if (clickOn(x + w - 36, py, 30, 10) && snapPage < pages - 1) {
                PacketDistributor.sendToServer(new CloudPackets.CloudPageC2S(snapPage + 1));
            }
            Ui.button(c, x + w - 36, py, 30, 10, snapPage < pages - 1, c.hovered(x + w - 36, py, 30, 10));
            Ui.buttonLabel(c, x + w - 36, py, 30, 10, "▶", snapPage < pages - 1);

            // 網格 5×6，每格 20px（手機 120 寬放 5 格，平板更寬自動放大）
            ItemStack[] slots = SNAP_SLOTS.get();
            int cell = Math.min(28, (w - 8) / 5);
            int gx0 = x + (w - 5 * cell) / 2;
            int gy0 = y + 24;
            for (int r = 0; r < 6; r++) {
                for (int col = 0; col < 5; col++) {
                    int idx = r * 5 + col;
                    if (idx >= 30) break;
                    int global = snapPage * 30 + idx;
                    if (global >= tier.slots()) break;
                    int gx = gx0 + col * cell;
                    int gy = gy0 + r * cell;
                    boolean sel = selected == global;
                    Ui.fill(g, gx + 1, gy + 1, cell - 2, cell - 2,
                            sel ? 0xFF3A5A8A : 0xFF1A1F26);
                    Ui.border(c, gx + 1, gy + 1, cell - 2, cell - 2,
                            sel ? s.accentColor() : s.buttonDisabledColor());
                    ItemStack st = idx < slots.length ? slots[idx] : ItemStack.EMPTY;
                    if (st != null && !st.isEmpty()) {
                        g.renderItem(st, gx + 4, gy + 4);
                        g.renderItemDecorations(c.font(), st, gx + 4, gy + 4);
                    }
                    if (clickOn(gx + 1, gy + 1, cell - 2, cell - 2)) {
                        selected = global;
                    }
                }
            }

            // 底部操作列
            int by = y + h - 16;
            Ui.hline(g, x, x + w, by - 1, s.buttonDisabledColor());
            if (selected >= 0 && selected < tier.slots()) {
                ItemStack st = selectedSlot();
                if (st != null && !st.isEmpty()) {
                    Ui.textClipped(c, itemName(st) + " ×" + st.getCount(),
                            x + 3, by + 3, s.titleColor(), x, by, w, 12);
                } else {
                    Ui.textClipped(c, "空格（副手物品可存入）", x + 3, by + 3, s.subtleColor(), x, by, w - 84, 12);
                }
                if (clickOn(x + w - 78, by, 36, 12)) {
                    PacketDistributor.sendToServer(new CloudPackets.CloudTakeC2S(selected));
                }
                Ui.button(c, x + w - 78, by, 36, 12, true, c.hovered(x + w - 78, by, 36, 12));
                Ui.buttonLabel(c, x + w - 78, by, 36, 12, "取出", true);
                if (clickOn(x + w - 40, by, 36, 12)) {
                    PacketDistributor.sendToServer(new CloudPackets.CloudPutC2S(selected));
                }
                Ui.button(c, x + w - 40, by, 36, 12, true, c.hovered(x + w - 40, by, 36, 12));
                Ui.buttonLabel(c, x + w - 40, by, 36, 12, "存入", true);
            } else {
                Ui.textClipped(c, "點格子選中，再取出／存入", x + 3, by + 3, s.subtleColor(), x, by, w - 84, 12);
            }

            if (System.currentTimeMillis() < toastUntil && !toast.isEmpty()) {
                Ui.drawCentered(c, toast, x, y + h - 14, w, 12, s.titleColor());
            }
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            if (upgrading) {
                upScroller.onWheel(amount, CloudTier.values().length * 22, 130);
                return true;
            }
            return false;
        }

        private ItemStack selectedSlot() {
            int idx = selected - snapPage * 30;
            ItemStack[] slots = SNAP_SLOTS.get();
            if (idx >= 0 && idx < slots.length) return slots[idx];
            return ItemStack.EMPTY;
        }

        private void renderUpgrade(com.november.mcphone.api.client.ui.PhoneCanvas c) {
            int x = c.x(), y = c.y(), w = c.width(), h = c.height();
            var s = c.style();
            GuiGraphics g = c.graphics();
            Ui.fill(g, x, y, w, h, s.screenBackground());
            Ui.textClipped(c, "☁️ 升級會員（30 天＝600 分鐘真實時間）",
                    x + 3, y + 2, s.titleColor(), x, y, w, 12);
            if (clickOn(x + w - 34, y + 1, 31, 10)) upgrading = false;
            Ui.button(c, x + w - 34, y + 1, 31, 10, true, c.hovered(x + w - 34, y + 1, 31, 10));
            Ui.buttonLabel(c, x + w - 34, y + 1, 31, 10, "返回", true);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            int listY = y + 14;
            int rowH = 22;
            int listH = h - 14 - 14;
            CloudTier cur = tier();
            upScroller.clamp(CloudTier.values().length * rowH, listH);
            int off = (int) upScroller.offset();
            for (int i = 0; i < CloudTier.values().length; i++) {
                CloudTier t = CloudTier.values()[i];
                int ry = listY + i * rowH - off;
                if (ry + rowH > y + h) break;
                if (ry < listY) continue;
                boolean isCur = t.order <= cur.order;
                Ui.fill(g, x + 2, ry, w - 4, rowH - 1,
                        isCur ? 0xFF1E3A2A : 0xFF161B22);
                Ui.hline(g, x + 2, x + w - 2, ry + rowH - 1, s.buttonDisabledColor());
                Ui.textClipped(c, t.label + (isCur ? " ✓" : ""),
                        x + 5, ry + 1, isCur ? s.accentColor() : s.titleColor(), x, ry, w - 62, rowH);
                Ui.textClipped(c, t.boxes + " 大箱（" + (t.boxes * 54) + " 格）  疊 " + t.stackLimit
                        + (t == CloudTier.NONE ? "  免費基礎" : "  " + t.priceLabel()),
                        x + 5, ry + 11, s.bodyColor(), x, ry, w - 62, rowH);
                boolean canBuy = t != CloudTier.NONE && t.order > cur.order && !purchasePending;
                if (canBuy && clickOn(x + w - 58, ry + 1, 54, rowH - 2)) {
                    purchasePending = true; // 防連點：收到快照回應後自動解除
                    PacketDistributor.sendToServer(new CloudPackets.CloudUpgradeC2S(t.ordinal()));
                    toast("已請求購買 " + t.label);
                }
                Ui.button(c, x + w - 58, ry + 1, 54, rowH - 2,
                        canBuy, c.hovered(x + w - 58, ry + 1, 54, rowH - 2));
                Ui.buttonLabel(c, x + w - 58, ry + 1, 54, rowH - 2,
                        isCur ? "已擁有" : "購買", canBuy);
            }
            Ui.drawCentered(c, "熔爐加速 VIP 2× SVIP 4×｜附魔台書架 VIP1=5 VIP2=10 VIP3+=15",
                    x, y + h - 12, w, 12, s.subtleColor());
        }

        private static String itemName(ItemStack st) {
            return st.getHoverName().getString();
        }

        private static String timeLeft(long ms) {
            if (ms <= 0) return "無會員";
            long s = ms / 1000;
            return String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
        }
    }
}
