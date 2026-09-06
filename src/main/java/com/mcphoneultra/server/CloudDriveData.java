package com.mcphoneultra.server;

import com.mcphoneultra.MCphoneUltra;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 網盤持久數據：每個玩家的槽位陣列（最大 128 大箱）、會員等級、會員到期
 * （真實時鐘毫秒）。存主世界 DataStorage，重啟不丟。
 */
public final class CloudDriveData extends SavedData {

    public static final String ID = "mcphone_ultra_cloud";
    /** 任何等級都配得上的最大格數（SVIP5 = 128×54） */
    public static final int MAX_SLOTS = 128 * 54;

    private final Map<UUID, ItemStack[]> slots = new HashMap<>();
    private final Map<UUID, Integer> tiers = new HashMap<>();
    private final Map<UUID, Long> expires = new HashMap<>();

    public CloudDriveData() {
    }

    public static CloudDriveData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage()
                .computeIfAbsent(new Factory<>(CloudDriveData::new, CloudDriveData::load), ID);
    }

    public static CloudDriveData load(CompoundTag tag, HolderLookup.Provider regs) {
        CloudDriveData d = new CloudDriveData();
        CompoundTag s = tag.getCompound("slots");
        for (String k : s.getAllKeys()) {
            ListTag list = s.getList(k, Tag.TAG_COMPOUND);
            ItemStack[] arr = new ItemStack[MAX_SLOTS];
            for (int i = 0; i < Math.min(list.size(), MAX_SLOTS); i++) {
                arr[i] = ItemStack.parseOptional(regs, list.getCompound(i));
            }
            d.slots.put(UUID.fromString(k), arr);
        }
        CompoundTag t = tag.getCompound("tiers");
        for (String k : t.getAllKeys()) {
            d.tiers.put(UUID.fromString(k), t.getInt(k));
        }
        CompoundTag e = tag.getCompound("expires");
        for (String k : e.getAllKeys()) {
            d.expires.put(UUID.fromString(k), e.getLong(k));
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider regs) {
        CompoundTag s = new CompoundTag();
        for (Map.Entry<UUID, ItemStack[]> e : slots.entrySet()) {
            ListTag list = new ListTag();
            for (ItemStack st : e.getValue()) {
                list.add(st == null || st.isEmpty()
                        ? new CompoundTag()
                        : st.saveOptional(regs));
            }
            s.put(e.getKey().toString(), list);
        }
        tag.put("slots", s);
        CompoundTag t = new CompoundTag();
        for (Map.Entry<UUID, Integer> e : tiers.entrySet()) {
            t.putInt(e.getKey().toString(), e.getValue());
        }
        tag.put("tiers", t);
        CompoundTag ex = new CompoundTag();
        for (Map.Entry<UUID, Long> e : expires.entrySet()) {
            ex.putLong(e.getKey().toString(), e.getValue());
        }
        tag.put("expires", ex);
        return tag;
    }

    /** 當前有效等級（過期自動降為無會員） */
    public CloudTier tierOf(ServerPlayer player) {
        UUID id = player.getUUID();
        long now = System.currentTimeMillis();
        Long exp = expires.get(id);
        if (exp == null || exp <= now) {
            tiers.remove(id);
            expires.remove(id);
            return CloudTier.NONE;
        }
        Integer ord = tiers.get(id);
        CloudTier[] all = CloudTier.values();
        int o = ord == null ? 0 : Math.max(0, Math.min(ord, all.length - 1));
        return all[o];
    }

    /** 剩餘毫秒（無會員 0） */
    public long remainingMs(ServerPlayer player) {
        UUID id = player.getUUID();
        Long exp = expires.get(id);
        if (exp == null) return 0;
        long left = exp - System.currentTimeMillis();
        return Math.max(0, left);
    }

    /** 設置/續費等級：30 天真實時間 */
    public void grant(ServerPlayer player, CloudTier tier) {
        UUID id = player.getUUID();
        long now = System.currentTimeMillis();
        long oldExp = expires.getOrDefault(id, 0L);
        // 更高級＝直接升並重置 30 天；同級＝續期疊加；更低級不允許（調用方已擋）
        if (tier.order >= tierOf(player).order) {
            tiers.put(id, tier.ordinal());
            expires.put(id, Math.max(now + CloudTier.DURATION_MS, oldExp + CloudTier.DURATION_MS));
        }
        setDirty();
    }

    /** 玩家槽位陣列（自動擴到等級容量） */
    public ItemStack[] slotsOf(ServerPlayer player) {
        UUID id = player.getUUID();
        ItemStack[] arr = slots.computeIfAbsent(id, k -> new ItemStack[MAX_SLOTS]);
        if (arr.length < CloudTier.SVIP5.slots()) {
            ItemStack[] grown = new ItemStack[CloudTier.SVIP5.slots()];
            System.arraycopy(arr, 0, grown, 0, arr.length);
            arr = grown;
            slots.put(id, arr);
        }
        return arr;
    }

    public void markChanged() {
        setDirty();
    }

    static void log(String msg) {
        MCphoneUltra.LOGGER.info("[Ultra 網盤] {}", msg);
    }
}
