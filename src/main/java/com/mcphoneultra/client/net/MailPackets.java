package com.mcphoneultra.client.net;

import com.mcphoneultra.server.MailData;
import com.mcphoneultra.server.MailData.MailEntry;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 物品郵件封包：打開信箱 / 寄信（副手=附件） / 領取附件 / 標記已讀，
 * 服務端回整份信箱清單。
 */
public final class MailPackets {

    private MailPackets() {
    }

    // ---- C2S ----

    public record MailOpenC2S() implements CustomPacketPayload {
        public static final Type<MailOpenC2S> TYPE =
                new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.november.mcphone.MCphone.MODID, "ultra_mail_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MailOpenC2S> STREAM_CODEC =
                StreamCodec.unit(new MailOpenC2S());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record MailSendC2S(String toName, String message) implements CustomPacketPayload {
        public static final Type<MailSendC2S> TYPE =
                new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.november.mcphone.MCphone.MODID, "ultra_mail_send"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MailSendC2S> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(64), MailSendC2S::toName,
                        ByteBufCodecs.stringUtf8(512), MailSendC2S::message,
                        MailSendC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record MailTakeC2S(int index) implements CustomPacketPayload {
        public static final Type<MailTakeC2S> TYPE =
                new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.november.mcphone.MCphone.MODID, "ultra_mail_take"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MailTakeC2S> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_INT, MailTakeC2S::index, MailTakeC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record MailReadC2S(int index) implements CustomPacketPayload {
        public static final Type<MailReadC2S> TYPE =
                new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.november.mcphone.MCphone.MODID, "ultra_mail_read"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MailReadC2S> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_INT, MailReadC2S::index, MailReadC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---- S2C ----

    public record MailListS2C(List<MailItem> mails) implements CustomPacketPayload {
        public static final Type<MailListS2C> TYPE =
                new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.november.mcphone.MCphone.MODID, "ultra_mail_list"));

        public record MailItem(String fromName, UUID fromUuid, String message,
                               List<ItemStack> attachments, long sentAt, boolean claimed) {
        }

        public static final StreamCodec<RegistryFriendlyByteBuf, MailListS2C> STREAM_CODEC =
                new StreamCodec<>() {
                    @Override
                    public MailListS2C decode(RegistryFriendlyByteBuf buf) {
                        int n = buf.readVarInt();
                        if (n > 200) n = 200;
                        List<MailItem> out = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            String fn = buf.readUtf(64);
                            UUID fu = buf.readUUID();
                            String msg = buf.readUtf(512);
                            int att = buf.readVarInt();
                            if (att > 9) att = 9;
                            List<ItemStack> items = new ArrayList<>(att);
                            for (int a = 0; a < att; a++) {
                                items.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
                            }
                            long ts = buf.readLong();
                            boolean cl = buf.readBoolean();
                            out.add(new MailItem(fn, fu, msg, items, ts, cl));
                        }
                        return new MailListS2C(out);
                    }

                    @Override
                    public void encode(RegistryFriendlyByteBuf buf, MailListS2C v) {
                        buf.writeVarInt(v.mails.size());
                        for (MailItem m : v.mails) {
                            buf.writeUtf(m.fromName, 64);
                            buf.writeUUID(m.fromUuid);
                            buf.writeUtf(m.message, 512);
                            buf.writeVarInt(m.attachments.size());
                            for (ItemStack s : m.attachments) {
                                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, s);
                            }
                            buf.writeLong(m.sentAt);
                            buf.writeBoolean(m.claimed);
                        }
                    }
                };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 收件人在線時的通知（App 靜態 receive 顯示 toast） */
    public record MailNotifyS2C(String fromName, int attachCount) implements CustomPacketPayload {
        public static final Type<MailNotifyS2C> TYPE =
                new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.november.mcphone.MCphone.MODID, "ultra_mail_notify"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MailNotifyS2C> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(64), MailNotifyS2C::fromName,
                        ByteBufCodecs.VAR_INT, MailNotifyS2C::attachCount,
                        MailNotifyS2C::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---- 服務端邏輯 ----

    public static void handleOpen(ServerPlayer player) {
        if (!com.november.mcphone.core.PhoneItem.isCarriedBy(player)) return;
        MailData data = MailData.get(player.serverLevel());
        List<MailEntry> box = data.mailOf(player.getUUID());
        List<MailListS2C.MailItem> items = new ArrayList<>(box.size());
        for (MailEntry m : box) {
            items.add(new MailListS2C.MailItem(m.fromName, m.fromUuid, m.message,
                    m.attachments, m.sentAt, m.claimed));
        }
        player.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                new MailListS2C(items)));
    }

    public static void handleSend(ServerPlayer player, String toName, String message) {
        if (!com.november.mcphone.core.PhoneItem.isCarriedBy(player)) return;
        String name = toName == null ? "" : toName.trim();
        if (name.isEmpty()) return;
        if (name.equals(player.getGameProfile().getName())) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("📮 不能寄給自己"));
            return;
        }
        ServerPlayer target = player.server.getPlayerList().getPlayerByName(name);
        if (target == null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("📮 找不到線上玩家「" + name + "」"));
            return;
        }
        // 附件＝發件人副手那疊
        List<ItemStack> att = new ArrayList<>();
        ItemStack off = player.getOffhandItem();
        if (!off.isEmpty()) {
            att.add(off.copy());
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, ItemStack.EMPTY);
        }
        String msg = message == null ? "" : message.trim();
        if (msg.length() > 500) msg = msg.substring(0, 500);
        MailData data = MailData.get(player.serverLevel());
        data.deliver(target.getUUID(), new MailEntry(
                player.getUUID(), player.getGameProfile().getName(), name, msg, att));
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "📮 已寄給 " + name + (att.isEmpty() ? "" : "（附 " + att.get(0).getHoverName().getString() + " ×" + att.get(0).getCount() + "）")));
        // 線上通知
        target.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                new MailNotifyS2C(player.getGameProfile().getName(), att.size())));
    }

    public static void handleTake(ServerPlayer player, int index) {
        if (!com.november.mcphone.core.PhoneItem.isCarriedBy(player)) return;
        MailData data = MailData.get(player.serverLevel());
        List<MailEntry> box = data.mailOf(player.getUUID());
        if (index < 0 || index >= box.size()) return;
        MailEntry m = box.get(index);
        if (m.claimed) return;
        boolean ok = true;
        for (ItemStack s : m.attachments) {
            if (s.isEmpty()) continue;
            if (!player.getInventory().add(s)) {
                ok = false;
                break;
            }
        }
        if (ok) {
            m.claimed = true;
            data.markChanged();
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("📮 附件已領取"));
            handleOpen(player);
        } else {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("📮 背包滿了，附件放不下"));
        }
    }

    public static void handleRead(ServerPlayer player, int index) {
        if (!com.november.mcphone.core.PhoneItem.isCarriedBy(player)) return;
        MailData data = MailData.get(player.serverLevel());
        List<MailEntry> box = data.mailOf(player.getUUID());
        if (index < 0 || index >= box.size()) return;
        MailEntry m = box.get(index);
        if (!m.claimed) {
            m.claimed = true;
            data.markChanged();
        }
    }
}
