package com.mcphoneultra.client.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** C2S：在手機上對綁定的村民發起遠程交易。 */
public record VillagerTradePacket(UUID villager) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<VillagerTradePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "ultra_villager_trade"));

    public static final StreamCodec<ByteBuf, VillagerTradePacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, VillagerTradePacket::villager,
                    VillagerTradePacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
