package com.mcphoneultra.server;

import com.mcphoneultra.client.net.CloudPackets;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 網盤服務端邏輯：快照、存取、升級、限速、開隨身菜單。 */
public final class CloudDriveServer {

    public static final int PER_PAGE = 45;

    /** 無會員限速：1 次操作／秒 */
    private static final Map<UUID, Long> lastOps = new HashMap<>();

    private CloudDriveServer() {
    }

    // ---- 網盤 ----

    public static void open(ServerPlayer p) {
        sendSnapshot(p, 0);
    }

    public static void page(ServerPlayer p, int page) {
        sendSnapshot(p, page);
    }

    private static void sendSnapshot(ServerPlayer p, int page) {
        CloudDriveData d = CloudDriveData.get(p.serverLevel());
        CloudTier tier = d.tierOf(p);
        int slots = tier.slots();
        int maxPage = Math.max(0, (slots - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, maxPage));
        ItemStack[] all = d.slotsOf(p);
        List<ItemStack> pageItems = new ArrayList<>(PER_PAGE);
        int end = Math.min((page + 1) * PER_PAGE, slots);
        for (int i = page * PER_PAGE; i < end; i++) {
            ItemStack s = all[i];
            pageItems.add(s == null ? ItemStack.EMPTY : s);
        }
        while (pageItems.size() < PER_PAGE) pageItems.add(ItemStack.EMPTY);
        PacketDistributor.sendToPlayer(p, new CloudPackets.CloudSnapshotS2C(
                tier.ordinal(), d.remainingMs(p), page, pageItems));
    }

    public static void take(ServerPlayer p, int slot) {
        CloudDriveData d = CloudDriveData.get(p.serverLevel());
        CloudTier tier = d.tierOf(p);
        if (slot < 0 || slot >= tier.slots()) return;
        if (!rateLimit(p, tier)) {
            msg(p, "無會員限速：每秒 1 次操作");
            return;
        }
        ItemStack[] all = d.slotsOf(p);
        ItemStack st = all[slot];
        if (st == null || st.isEmpty()) return;
        int take = Math.min(st.getCount(), 64); // 原版物品栏堆疊上限 64，一次一疊
        ItemStack give = st.copy();
        give.setCount(take);
        int placed = placeInInventory(p, give);
        if (placed <= 0) {
            msg(p, "背包滿了");
            return;
        }
        st.shrink(placed);
        if (st.getCount() <= 0) all[slot] = ItemStack.EMPTY;
        d.markChanged();
        sendSnapshot(p, slot / PER_PAGE);
    }

    public static void put(ServerPlayer p, int slot) {
        CloudDriveData d = CloudDriveData.get(p.serverLevel());
        CloudTier tier = d.tierOf(p);
        if (slot < 0 || slot >= tier.slots()) return;
        if (!rateLimit(p, tier)) {
            msg(p, "無會員限速：每秒 1 次操作");
            return;
        }
        ItemStack hand = p.getOffhandItem();
        if (hand.isEmpty()) {
            msg(p, "請把要存的物品放副手");
            return;
        }
        ItemStack[] all = d.slotsOf(p);
        ItemStack target = all[slot];
        int n = hand.getCount();
        if (target == null || target.isEmpty()) {
            ItemStack copy = hand.copy();
            copy.setCount(Math.min(n, tier.stackLimit));
            all[slot] = copy;
            hand.shrink(copy.getCount());
        } else if (ItemStack.isSameItemSameComponents(target, hand)
                && target.getCount() < tier.stackLimit) {
            int add = Math.min(n, tier.stackLimit - target.getCount());
            target.grow(add);
            hand.shrink(add);
        } else {
            msg(p, "該格已滿或物品不同");
            return;
        }
        p.inventoryMenu.broadcastChanges();
        d.markChanged();
        sendSnapshot(p, slot / PER_PAGE);
    }

    public static void upgrade(ServerPlayer p, int ordinal) {
        CloudTier[] all = CloudTier.values();
        if (ordinal <= 0 || ordinal >= all.length) return;
        CloudTier target = all[ordinal];
        CloudDriveData d = CloudDriveData.get(p.serverLevel());
        CloudTier cur = d.tierOf(p);
        if (target.order <= cur.order) {
            msg(p, "你已是同級或更高級");
            return;
        }
        ItemStack price = target.price();
        if (!takeItems(p, price)) {
            msg(p, "缺少「" + target.priceLabel() + "」");
            return;
        }
        d.grant(p, target);
        msg(p, "已升級 " + target.label + "（30 天）");
        sendSnapshot(p, 0);
    }

    // ---- 隨身菜單 ----

    public static void openCraft(ServerPlayer p) {
        if (!phoneCheck(p)) return;
        p.openMenu(new SimpleMenuProvider(
                (id, inv, pl) -> new CraftingMenu(id, inv,
                        ContainerLevelAccess.create(p.serverLevel(), p.blockPosition())),
                Component.translatable("container.crafting")));
    }

    public static void openFurnace(ServerPlayer p) {
        if (!phoneCheck(p)) return;
        FurnaceServer.open(p);
    }

    public static void openEnchant(ServerPlayer p) {
        if (!phoneCheck(p)) return;
        CloudTier tier = CloudDriveData.get(p.serverLevel()).tierOf(p);
        int books = tier.enchantBooks();
        p.openMenu(new SimpleMenuProvider(
                (id, inv, pl) -> new UltraEnchantMenu(id, inv,
                        ContainerLevelAccess.create(p.serverLevel(), p.blockPosition()), books),
                Component.translatable("container.enchant")));
    }

    private static boolean phoneCheck(ServerPlayer p) {
        if (!com.november.mcphone.core.PhoneItem.isCarriedBy(p)) {
            msg(p, "身上沒有手機");
            return false;
        }
        return true;
    }

    // ---- 內部 ----

    private static boolean rateLimit(ServerPlayer p, CloudTier tier) {
        if (tier.isVip()) return true;
        long now = System.currentTimeMillis();
        Long last = lastOps.get(p.getUUID());
        if (last != null && now - last < 1000) return false;
        lastOps.put(p.getUUID(), now);
        return true;
    }

    /** 先統計再扣，避免部分扣減 */
    private static boolean takeItems(ServerPlayer p, ItemStack price) {
        Container inv = p.getInventory();
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(price.getItem())) total += s.getCount();
        }
        if (total < price.getCount()) return false;
        int need = price.getCount();
        for (int i = 0; i < 36 && need > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(price.getItem())) {
                int take = Math.min(need, s.getCount());
                s.shrink(take);
                need -= take;
                if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
            }
        }
        inv.setChanged();
        p.inventoryMenu.broadcastChanges();
        return true;
    }

    private static int placeInInventory(ServerPlayer p, ItemStack give) {
        Container inv = p.getInventory();
        int n = give.getCount();
        for (int i = 0; i < 36 && n > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, give) && s.getCount() < 64) {
                int add = Math.min(n, 64 - s.getCount());
                s.grow(add);
                n -= add;
            }
        }
        for (int i = 0; i < 36 && n > 0; i++) {
            if (inv.getItem(i).isEmpty()) {
                int add = Math.min(n, 64);
                ItemStack copy = give.copy();
                copy.setCount(add);
                inv.setItem(i, copy);
                n -= add;
            }
        }
        return give.getCount() - n;
    }

    private static void msg(ServerPlayer p, String s) {
        p.displayClientMessage(Component.literal(s).withStyle(ChatFormatting.YELLOW), true);
    }
}
