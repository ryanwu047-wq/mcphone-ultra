package com.mcphoneultra.client.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * ☎ 電話封包：action 0=撥號 1=掛斷，targetName 為對方玩家名（掛斷可空）。
 */
public final class PhoneCallPacket {

    private PhoneCallPacket() {
    }

    public record PhoneCallC2S(int action, String targetName) implements CustomPacketPayload {
        public static final Type<PhoneCallC2S> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "ultra_phone_call"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PhoneCallC2S> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, PhoneCallC2S::action,
                        ByteBufCodecs.stringUtf8(64), PhoneCallC2S::targetName,
                        PhoneCallC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
