package com.mcphoneultra.client.app;

import com.mcphoneultra.client.io.Paths;
import com.mcphoneultra.client.io.Store;
import com.mcphoneultra.client.ui.Scroller;
import com.mcphoneultra.client.ui.Ui;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 複製：把副手物品的完整数据（含 NBT）複製存進手机。
 *
 * <ul>
 *   <li>每次複製消耗 64 鑽石（从手机里的鑽石餘額扣，餘額可「存入 / 領出」）</li>
 *   <li>複製結果以 NBT 保存到 {@code config/mcphone/ultra/copy/saves/*.nbt}</li>
 *   <li>序列化后的 NBT 不可大於 5KB，超限拒絕</li>
 * </ul>
 *
 * <p>鑽石操作：单人游戏时直接操作整合伺服器的玩家背包（真实扣/给）；
 * 多人游戏退化为仅操作本机客户端镜像（跨存档不同步）。
 */
public final class CopyApp extends BaseApp {

    public CopyApp() {
        super("copy", false);
    }

    @Override
    protected IPhonePage createPage() {
        return new CopyPage();
    }

    private static final class CopyPage extends ClickablePage {

        private static final int COPY_COST = 64;          // 每次複製消耗 64 鑽石
        private static final int NBT_LIMIT = 5 * 1024;    // NBT 不可大於 5KB
        private static final Path SAVES = Paths.dir("copy", "saves");
        private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

        private final Store store = Store.of("copy");
        private final List<Path> saves = new ArrayList<>();
        private final Scroller scroller = new Scroller();
        private String toast = "";
        private long toastUntil;

        private Path viewing;
        private String viewInfo = "";

        private void toast(String s) {
            toast = s;
            toastUntil = System.currentTimeMillis() + 2000;
        }

        private int balance() {
            return store.getInt("diamonds", 0);
        }

        private void setBalance(int v) {
            store.setInt("diamonds", Math.max(0, v));
        }

        private void reload() {
            saves.clear();
            try (var stream = Files.list(SAVES)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".nbt")).forEach(saves::add);
            } catch (IOException ignored) {
            }
            saves.sort(Comparator.comparing((Path p) -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis();
                } catch (IOException e) {
                    return 0L;
                }
            }).reversed());
            scroller.reset();
        }

        /** 操作背包的玩家：单人游戏取整合伺服器玩家（真实），多人退化为本机镜像。 */
        private static Player inventoryPlayer() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getSingleplayerServer() != null && mc.player != null) {
                ServerPlayer sp = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                if (sp != null) return sp;
            }
            return mc.player;
        }

        private static int countDiamonds(Player p) {
            int n = 0;
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                ItemStack s = p.getInventory().getItem(i);
                if (!s.isEmpty() && s.is(Items.DIAMOND)) n += s.getCount();
            }
            return n;
        }

        private static void removeDiamonds(Player p, int count) {
            int need = count;
            for (int i = 0; i < p.getInventory().getContainerSize() && need > 0; i++) {
                ItemStack s = p.getInventory().getItem(i);
                if (s.isEmpty() || !s.is(Items.DIAMOND)) continue;
                int take = Math.min(need, s.getCount());
                s.shrink(take);
                need -= take;
            }
        }

        private void depositAll() {
            Player p = inventoryPlayer();
            if (p == null) {
                toast("無法取得玩家");
                return;
            }
            int d = countDiamonds(p);
            if (d <= 0) {
                toast("背包沒有鑽石");
                return;
            }
            removeDiamonds(p, d);
            setBalance(balance() + d);
            toast("已存入 " + d + " 顆鑽石");
        }

        private void withdrawAll() {
            Player p = inventoryPlayer();
            if (p == null) {
                toast("無法取得玩家");
                return;
            }
            int bal = balance();
            if (bal <= 0) {
                toast("沒有可領的鑽石");
                return;
            }
            int remaining = bal;
            while (remaining > 0) {
                int n = Math.min(remaining, 64);
                ItemStack add = new ItemStack(Items.DIAMOND, n);
                if (!p.getInventory().add(add)) {
                    toast("背包已滿");
                    break;
                }
                remaining -= n;
            }
            setBalance(remaining);
            toast("已領出 " + (bal - remaining) + " 顆鑽石");
        }

        private static byte[] compress(CompoundTag tag) {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                NbtIo.writeCompressed(tag, out);
                return out.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException("NBT 压缩失败", e);
            }
        }

        private void doCopy() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                toast("無法取得玩家");
                return;
            }
            ItemStack off = mc.player.getOffhandItem();
            if (off.isEmpty()) {
                toast("副手沒有物品");
                return;
            }
            if (balance() < COPY_COST) {
                toast("鑽石不足（複製需 64 鑽石）");
                return;
            }
            RegistryAccess regs = mc.level.registryAccess();
            CompoundTag tag = (CompoundTag) off.save(regs);
            byte[] data = compress(tag);
            if (data.length > NBT_LIMIT) {
                toast("NBT 過大（" + (data.length / 1024f) + "KB > 5KB）");
                return;
            }
            String name = off.getHoverName().getString();
            String file = Paths.safeName(name) + "_" + LocalDateTime.now().format(TS) + ".nbt";
            try {
                NbtIo.writeCompressed(tag, SAVES.resolve(file));
            } catch (IOException e) {
                toast("儲存失敗");
                return;
            }
            setBalance(balance() - COPY_COST);
            reload();
            toast("已複製「" + name + "」（" + data.length + "B，扣 64 鑽石）");
        }

        private void openView(Path p) {
            try {
                Minecraft mc = Minecraft.getInstance();
                CompoundTag tag = NbtIo.readCompressed(p, NbtAccounter.unlimitedHeap());
                long size = Files.size(p);
                String itemName = "（無法解析）";
                if (mc.level != null) {
                    itemName = ItemStack.parse(mc.level.registryAccess(), tag)
                            .map(s -> s.getHoverName().getString()).orElse("（無法解析）");
                }
                viewInfo = "物品：" + itemName + "\n\nNBT：" + size + " B（上限 5120 B）\n\n"
                        + "檔案：" + p.getFileName();
                viewing = p;
            } catch (IOException e) {
                toast("讀取失敗");
            }
        }

        private void deleteSave(Path p) {
            try {
                Files.deleteIfExists(p);
                if (viewing != null && viewing.equals(p)) {
                    viewing = null;
                    viewInfo = "";
                }
                reload();
                toast("已刪除");
            } catch (IOException e) {
                toast("刪除失敗");
            }
        }

        @Override
        public void onOpen() {
            reload();
        }

        @Override
        public void render(PhoneCanvas c) {
            int x = c.x();
            int y = c.y();
            int w = c.width();
            int h = c.height();
            var s = c.style();
            GuiGraphics g = c.graphics();

            if (viewing != null) {
                renderView(c, x, y, w, h);
                return;
            }

            Ui.fill(g, x, y, w, h, s.screenBackground());

            Ui.text(c, "複製", x + 3, y + 2, s.titleColor());
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            // ── 鑽石餘額 ──
            int by = y + 13;
            if (clickOn(x + 1, by, 44, 12)) {
                depositAll();
            }
            Ui.button(c, x + 1, by, 44, 12, true, c.hovered(x + 1, by, 44, 12));
            Ui.buttonLabel(c, x + 1, by, 44, 12, "存鑽石", true);

            if (clickOn(x + 47, by, 44, 12)) {
                withdrawAll();
            }
            Ui.button(c, x + 47, by, 44, 12, true, c.hovered(x + 47, by, 44, 12));
            Ui.buttonLabel(c, x + 47, by, 44, 12, "領鑽石", true);

            Ui.textClipped(c, "餘額：" + balance() + " ◆", x + 95, by + 2, s.accentColor(), x, by, w, 12);

            // ── 副手物品 ──
            int sy = by + 15;
            Ui.hline(g, x, x + w, sy - 2, s.buttonDisabledColor());
            Minecraft mc = Minecraft.getInstance();
            ItemStack off = (mc.player != null) ? mc.player.getOffhandItem() : ItemStack.EMPTY;
            String offLine;
            if (off.isEmpty()) {
                offLine = "副手：空";
            } else if (mc.level != null) {
                byte[] data = compress((CompoundTag) off.save(mc.level.registryAccess()));
                offLine = "副手：" + off.getHoverName().getString() + " ×" + off.getCount()
                        + "（NBT " + data.length + "B）";
            } else {
                offLine = "副手：" + off.getHoverName().getString() + " ×" + off.getCount();
            }
            Ui.textClipped(c, offLine, x + 3, sy + 1, off.isEmpty() ? s.subtleColor() : s.bodyColor(),
                    x, sy, w - 4, 12);

            int cy = sy + 14;
            boolean canCopy = !off.isEmpty() && balance() >= COPY_COST;
            if (clickOn(x + 1, cy, w - 2, 12)) {
                doCopy();
            }
            Ui.button(c, x + 1, cy, w - 2, 12, canCopy, c.hovered(x + 1, cy, w - 2, 12));
            Ui.buttonLabel(c, x + 1, cy, w - 2, 12,
                    canCopy ? "複製（扣 64 鑽石）" : "複製（需要 64 鑽石）", canCopy);

            // ── 已保存 ──
            int ly = cy + 16;
            Ui.hline(g, x, x + w, ly - 2, s.buttonDisabledColor());
            Ui.textClipped(c, "已保存（" + saves.size() + "）", x + 3, ly + 1, s.titleColor(), x, ly, w, 12);

            int listY = ly + 14;
            int listH = h - (listY - y) - 14;
            int rowH = 12;
            scroller.clamp(saves.size() * rowH, listH);
            int scrollOff = (int) scroller.offset();

            for (int i = 0; i < saves.size(); i++) {
                int ry = listY + i * rowH - scrollOff;
                if (ry + rowH < listY || ry > listY + listH) continue;
                Path p = saves.get(i);
                String name = p.getFileName().toString();
                boolean hover = c.hovered(x, ry, w, rowH);
                if (hover) Ui.fill(g, x, ry, w, rowH, s.pressedOverlay());

                if (clickOn(x, ry, w - 14, rowH)) {
                    openView(p);
                }
                if (clickOn(x + w - 14, ry, 14, rowH)) {
                    deleteSave(p);
                }
                Ui.textClipped(c, truncate(name, 14), x + 3, ry + 1, s.bodyColor(), x, ry, w, rowH);
                Ui.textClipped(c, sizeStr(p), x + 66, ry + 2, s.subtleColor(), x, ry, w, rowH);
                Ui.hline(g, x + 2, x + w - 2, ry + rowH - 1, s.buttonDisabledColor());
            }
            Ui.scrollbar(c, x + w - 3, listY, listH, saves.size() * rowH, scrollOff);

            renderToast(c, x, y, w, h);
        }

        private void renderView(PhoneCanvas c, int x, int y, int w, int h) {
            var s = c.style();
            GuiGraphics g = c.graphics();
            Ui.fill(g, x, y, w, h, s.screenBackground());
            Ui.textClipped(c, "已保存：「" + viewing.getFileName() + "」", x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            if (clickOn(x + 1, y + 13, 52, 12)) {
                viewing = null;
                viewInfo = "";
            }
            Ui.button(c, x + 1, y + 13, 52, 12, true, c.hovered(x + 1, y + 13, 52, 12));
            Ui.buttonLabel(c, x + 1, y + 13, 52, 12, "← 返回", true);

            if (clickOn(x + 55, y + 13, w - 56, 12)) {
                deleteSave(viewing);
            }
            Ui.button(c, x + 55, y + 13, w - 56, 12, true, c.hovered(x + 55, y + 13, w - 56, 12));
            Ui.buttonLabel(c, x + 55, y + 13, w - 56, 12, "刪除", true);

            int ty = y + 30;
            int rowH = c.font().lineHeight + 2;
            int line = 0;
            for (String ln : viewInfo.split("\n", -1)) {
                Ui.textClipped(c, ln, x + 3, ty + line * rowH, s.bodyColor(), x, ty, w - 4, h);
                line++;
            }
            renderToast(c, x, y, w, h);
        }

        private static String truncate(String s, int n) {
            if (s.length() <= n) return s;
            return s.substring(0, n - 1) + "…";
        }

        private static String sizeStr(Path p) {
            try {
                long len = Files.size(p);
                if (len < 1024) return len + "B";
                return (len / 1024f) + "KB";
            } catch (IOException e) {
                return "";
            }
        }

        private void renderToast(PhoneCanvas c, int x, int y, int w, int h) {
            if (System.currentTimeMillis() < toastUntil && !toast.isEmpty()) {
                int tw = c.font().width(toast) + 8;
                int tx = x + (w - tw) / 2;
                int ty = y + h - 20;
                Ui.fill(c.graphics(), tx, ty, tw, 12, 0xE0202830);
                Ui.text(c, toast, tx + 4, ty + 1, c.style().titleColor());
            }
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            if (viewing != null) return true;
            scroller.onWheel(amount, saves.size() * 12, 130);
            return true;
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            return false;
        }

        @Override
        public boolean onBack() {
            if (viewing != null) {
                viewing = null;
                viewInfo = "";
                return true;
            }
            return false;
        }
    }
}
