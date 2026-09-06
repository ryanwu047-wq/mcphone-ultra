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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 物品郵件持久數據：每個收件人一封封郵件（寄件人、訊息、附件、時間、已讀）。
 * 像末影箱一樣跨會話保存——重啟伺服器郵件不丟。
 */
public final class MailData extends SavedData {

    public static final String ID = "mcphone_ultra_mail";
    /** 每人信箱上限（擋住刷爆存檔） */
    public static final int MAX_MAILS = 200;

    public static final class MailEntry {
        public UUID fromUuid;
        public String fromName;
        public String toName;
        public String message;
        public List<ItemStack> attachments = new ArrayList<>();
        public long sentAt;
        public boolean claimed;

        public MailEntry() {
        }

        public MailEntry(UUID fromUuid, String fromName, String toName,
                         String message, List<ItemStack> attachments) {
            this.fromUuid = fromUuid;
            this.fromName = fromName;
            this.toName = toName;
            this.message = message;
            this.attachments = attachments;
            this.sentAt = System.currentTimeMillis();
        }

        public CompoundTag save(HolderLookup.Provider regs) {
            CompoundTag t = new CompoundTag();
            t.putUUID("fu", fromUuid);
            t.putString("fn", fromName);
            t.putString("tn", toName);
            t.putString("msg", message);
            ListTag at = new ListTag();
            for (ItemStack s : attachments) {
                at.add(s.isEmpty() ? new CompoundTag() : s.saveOptional(regs));
            }
            t.put("att", at);
            t.putLong("ts", sentAt);
            t.putBoolean("cl", claimed);
            return t;
        }

        public static MailEntry load(CompoundTag t, HolderLookup.Provider regs) {
            MailEntry m = new MailEntry();
            m.fromUuid = t.getUUID("fu");
            m.fromName = t.getString("fn");
            m.toName = t.getString("tn");
            m.message = t.getString("msg");
            ListTag at = t.getList("att", Tag.TAG_COMPOUND);
            for (int i = 0; i < at.size() && i < 9; i++) {
                m.attachments.add(ItemStack.parseOptional(regs, at.getCompound(i)));
            }
            m.sentAt = t.getLong("ts");
            m.claimed = t.getBoolean("cl");
            return m;
        }
    }

    private final Map<UUID, List<MailEntry>> mailboxes = new HashMap<>();

    public MailData() {
    }

    public static MailData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage()
                .computeIfAbsent(new Factory<>(MailData::new, MailData::load), ID);
    }

    public static MailData load(CompoundTag tag, HolderLookup.Provider regs) {
        MailData d = new MailData();
        CompoundTag m = tag.getCompound("mail");
        for (String k : m.getAllKeys()) {
            ListTag list = m.getList(k, Tag.TAG_COMPOUND);
            List<MailEntry> arr = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                arr.add(MailEntry.load(list.getCompound(i), regs));
            }
            d.mailboxes.put(UUID.fromString(k), arr);
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider regs) {
        CompoundTag m = new CompoundTag();
        for (Map.Entry<UUID, List<MailEntry>> e : mailboxes.entrySet()) {
            ListTag list = new ListTag();
            for (MailEntry mail : e.getValue()) {
                list.add(mail.save(regs));
            }
            m.put(e.getKey().toString(), list);
        }
        tag.put("mail", m);
        return tag;
    }

    public List<MailEntry> mailOf(UUID receiver) {
        return mailboxes.computeIfAbsent(receiver, k -> new ArrayList<>());
    }

    public void deliver(UUID receiver, MailEntry mail) {
        List<MailEntry> box = mailOf(receiver);
        if (box.size() >= MAX_MAILS) {
            box.remove(box.size() - 1);
        }
        box.add(0, mail);
        setDirty();
    }

    public void markChanged() {
        setDirty();
    }

    static void log(String msg) {
        MCphoneUltra.LOGGER.info("[Ultra 郵件] {}", msg);
    }
}
