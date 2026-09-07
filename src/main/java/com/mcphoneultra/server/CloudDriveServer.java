package com.mcphoneultra.server;

import com.mcphoneultra.client.net.CloudPackets;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
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

    public static final int PER_PAGE = 30;
    /** 單格 NBT 大小上限：超過就拒絕存入（防惡意 100 頁書塞爆快照封包／存檔） */
    public static final long MAX_ITEM_NBT = 256L * 1024;

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
            // 防護：舊資料／異常物品 NBT 過大時，快照以空格代替，避免封包爆量
            if (s != null && !s.isEmpty()
                    && estimateNbtSize(s.saveOptional(p.serverLevel().registryAccess())) > MAX_ITEM_NBT) {
                pageItems.add(ItemStack.EMPTY);
            } else {
                pageItems.add(s == null ? ItemStack.EMPTY : s);
            }
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
        // 防護：NBT 過大的物品（如 100 頁書）禁止存入，避免快照封包爆量
        if (estimateNbtSize(hand.saveOptional(p.serverLevel().registryAccess())) > MAX_ITEM_NBT) {
            msg(p, "物品太大（含大量 NBT），無法存入網盤");
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
                (id, inv, pl) -> new UltraCraftingMenu(id, inv,
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

    public static void openSmithing(ServerPlayer p) {
        if (!phoneCheck(p)) return;
        p.openMenu(new SimpleMenuProvider(
                (id, inv, pl) -> new UltraSmithingMenu(id, inv,
                        ContainerLevelAccess.create(p.serverLevel(), p.blockPosition())),
                Component.translatable("container.smithing")));
    }

    public static void openCartography(ServerPlayer p) {
        if (!phoneCheck(p)) return;
        p.openMenu(new SimpleMenuProvider(
                (id, inv, pl) -> new UltraCartographyMenu(id, inv,
                        ContainerLevelAccess.create(p.serverLevel(), p.blockPosition())),
                Component.translatable("container.cartography_table")));
    }

    public static void openGrindstone(ServerPlayer p) {
        if (!phoneCheck(p)) return;
        p.openMenu(new SimpleMenuProvider(
                (id, inv, pl) -> new UltraGrindstoneMenu(id, inv,
                        ContainerLevelAccess.create(p.serverLevel(), p.blockPosition())),
                Component.translatable("container.grindstone_title")));
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

    /** 估算 NBT 大小（位元組），用於限制異常大物品存入網盤。 */
    private static long estimateNbtSize(Tag tag) {
        if (tag == null) return 0;
        if (tag instanceof CompoundTag c) {
            long size = 3;
            for (String k : c.getAllKeys()) {
                size += k.length() * 2L + 3 + estimateNbtSize(c.get(k));
            }
            return size;
        }
        if (tag instanceof ListTag l) {
            long size = 5;
            for (Tag t : l) size += estimateNbtSize(t);
            return size;
        }
        if (tag instanceof StringTag s) return s.getAsString().length() * 2L + 4;
        if (tag instanceof NumericTag) return 9;
        if (tag instanceof ByteArrayTag b) return b.getAsByteArray().length + 5L;
        if (tag instanceof IntArrayTag i) return i.getAsIntArray().length * 4L + 5;
        if (tag instanceof LongArrayTag l) return l.getAsLongArray().length * 8L + 5;
        return 16;
    }

    private static void msg(ServerPlayer p, String s) {
        p.displayClientMessage(Component.literal(s).withStyle(ChatFormatting.YELLOW), true);
    }
}
