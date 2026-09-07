package com.mcphoneultra.client.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** 網盤相關全部封包，集中定義。 */
public final class CloudPackets {

    private CloudPackets() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String name) {
        return new CustomPacketPayload.Type<>(
                ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "ultra_" + name));
    }

    /** ItemStack 列表 codec（上限 64 格） */
    public static final StreamCodec<RegistryFriendlyByteBuf, List<ItemStack>> ITEM_LIST =
            new StreamCodec<>() {
                @Override
                public List<ItemStack> decode(RegistryFriendlyByteBuf buf) {
                    int n = buf.readVarInt();
                    if (n > 64) n = 64;
                    List<ItemStack> out = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        out.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
                    }
                    return out;
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, List<ItemStack> v) {
                    buf.writeVarInt(v.size());
                    for (ItemStack s : v) {
                        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, s);
                    }
                }
            };

    // ---- C2S ----

    /** 打開網盤（服務端回第 0 頁快照） */
    public record CloudOpenC2S() implements CustomPacketPayload {
        public static final Type<CloudOpenC2S> TYPE = payloadType("cloud_open");
        public static final StreamCodec<RegistryFriendlyByteBuf, CloudOpenC2S> STREAM_CODEC =
                StreamCodec.unit(new CloudOpenC2S());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 翻頁 */
    public record CloudPageC2S(int page) implements CustomPacketPayload {
        public static final Type<CloudPageC2S> TYPE = payloadType("cloud_page");
        public static final StreamCodec<RegistryFriendlyByteBuf, CloudPageC2S> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_INT, CloudPageC2S::page, CloudPageC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 取出第 slot 格（一疊≤64 到背包） */
    public record CloudTakeC2S(int slot) implements CustomPacketPayload {
        public static final Type<CloudTakeC2S> TYPE = payloadType("cloud_take");
        public static final StreamCodec<RegistryFriendlyByteBuf, CloudTakeC2S> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_INT, CloudTakeC2S::slot, CloudTakeC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 把副手物品放入第 slot 格 */
    public record CloudPutC2S(int slot) implements CustomPacketPayload {
        public static final Type<CloudPutC2S> TYPE = payloadType("cloud_put");
        public static final StreamCodec<RegistryFriendlyByteBuf, CloudPutC2S> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_INT, CloudPutC2S::slot, CloudPutC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 購買會員等級 */
    public record CloudUpgradeC2S(int tierOrdinal) implements CustomPacketPayload {
        public static final Type<CloudUpgradeC2S> TYPE = payloadType("cloud_upgrade");
        public static final StreamCodec<RegistryFriendlyByteBuf, CloudUpgradeC2S> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_INT, CloudUpgradeC2S::tierOrdinal,
                        CloudUpgradeC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 開隨身合成台 */
    public record CraftOpenC2S() implements CustomPacketPayload {
        public static final Type<CraftOpenC2S> TYPE = payloadType("craft_open");
        public static final StreamCodec<RegistryFriendlyByteBuf, CraftOpenC2S> STREAM_CODEC =
                StreamCodec.unit(new CraftOpenC2S());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 開隨身熔爐 */
    public record FurnaceOpenC2S() implements CustomPacketPayload {
        public static final Type<FurnaceOpenC2S> TYPE = payloadType("furnace_open");
        public static final StreamCodec<RegistryFriendlyByteBuf, FurnaceOpenC2S> STREAM_CODEC =
                StreamCodec.unit(new FurnaceOpenC2S());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 開隨身附魔台 */
    public record EnchantOpenC2S() implements CustomPacketPayload {
        public static final Type<EnchantOpenC2S> TYPE = payloadType("enchant_open");
        public static final StreamCodec<RegistryFriendlyByteBuf, EnchantOpenC2S> STREAM_CODEC =
                StreamCodec.unit(new EnchantOpenC2S());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 熔爐：副手物品放入輸入槽 */
    public record FurnacePutInputC2S() implements CustomPacketPayload {
        public static final Type<FurnacePutInputC2S> TYPE = payloadType("furnace_put_input");
        public static final StreamCodec<RegistryFriendlyByteBuf, FurnacePutInputC2S> STREAM_CODEC =
                StreamCodec.unit(new FurnacePutInputC2S());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 熔爐：副手物品放入燃料槽 */
    public record FurnacePutFuelC2S() implements CustomPacketPayload {
        public static final Type<FurnacePutFuelC2S> TYPE = payloadType("furnace_put_fuel");
        public static final StreamCodec<RegistryFriendlyByteBuf, FurnacePutFuelC2S> STREAM_CODEC =
                StreamCodec.unit(new FurnacePutFuelC2S());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 熔爐：取出產出 */
    public record FurnaceTakeOutputC2S() implements CustomPacketPayload {
        public static final Type<FurnaceTakeOutputC2S> TYPE = payloadType("furnace_take_output");
        public static final StreamCodec<RegistryFriendlyByteBuf, FurnaceTakeOutputC2S> STREAM_CODEC =
                StreamCodec.unit(new FurnaceTakeOutputC2S());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 熔爐：關閉（剩餘物品還玩家） */
    public record FurnaceCloseC2S() implements CustomPacketPayload {
        public static final Type<FurnaceCloseC2S> TYPE = payloadType("furnace_close");
        public static final StreamCodec<RegistryFriendlyByteBuf, FurnaceCloseC2S> STREAM_CODEC =
                StreamCodec.unit(new FurnaceCloseC2S());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---- S2C ----

    /** 熔爐狀態（手機內 UI） */
    /** 開隨身鍛造台 */
    public record SmithingOpenC2S() implements CustomPacketPayload {
        public static final Type<SmithingOpenC2S> TYPE = payloadType("smithing_open");
        public static final StreamCodec<RegistryFriendlyByteBuf, SmithingOpenC2S> STREAM_CODEC =
                StreamCodec.unit(new SmithingOpenC2S());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 開隨身製圖台 */
    public record CartographyOpenC2S() implements CustomPacketPayload {
        public static final Type<CartographyOpenC2S> TYPE = payloadType("cartography_open");
        public static final StreamCodec<RegistryFriendlyByteBuf, CartographyOpenC2S> STREAM_CODEC =
                StreamCodec.unit(new CartographyOpenC2S());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 開隨身砂輪 */
    public record GrindstoneOpenC2S() implements CustomPacketPayload {
        public static final Type<GrindstoneOpenC2S> TYPE = payloadType("grindstone_open");
        public static final StreamCodec<RegistryFriendlyByteBuf, GrindstoneOpenC2S> STREAM_CODEC =
                StreamCodec.unit(new GrindstoneOpenC2S());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 手電筒開關 */
    public record FlashlightToggleC2S() implements CustomPacketPayload {
        public static final Type<FlashlightToggleC2S> TYPE = payloadType("flashlight_toggle");
        public static final StreamCodec<RegistryFriendlyByteBuf, FlashlightToggleC2S> STREAM_CODEC =
                StreamCodec.unit(new FlashlightToggleC2S());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 地圖畫上傳：128×128 地圖顏色索引（每格 0-63） */
    public record MapArtUploadC2S(byte[] colors) implements CustomPacketPayload {
        public static final Type<MapArtUploadC2S> TYPE = payloadType("mapart_upload");
        public static final StreamCodec<RegistryFriendlyByteBuf, MapArtUploadC2S> STREAM_CODEC =
                new StreamCodec<>() {
                    @Override
                    public MapArtUploadC2S decode(RegistryFriendlyByteBuf buf) {
                        int n = buf.readUnsignedShort();
                        if (n > 128 * 128) n = 128 * 128;
                        byte[] b = new byte[n];
                        buf.readBytes(b);
                        return new MapArtUploadC2S(b);
                    }

                    @Override
                    public void encode(RegistryFriendlyByteBuf buf, MapArtUploadC2S v) {
                        buf.writeShort(v.colors.length);
                        buf.writeBytes(v.colors);
                    }
                };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record FurnaceStateS2C(ItemStack input, ItemStack fuel, ItemStack output,
                                  int progress, int burnTicks, int speed) implements CustomPacketPayload {
        public static final Type<FurnaceStateS2C> TYPE = payloadType("furnace_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, FurnaceStateS2C> STREAM_CODEC =
                StreamCodec.composite(
                        ItemStack.OPTIONAL_STREAM_CODEC, FurnaceStateS2C::input,
                        ItemStack.OPTIONAL_STREAM_CODEC, FurnaceStateS2C::fuel,
                        ItemStack.OPTIONAL_STREAM_CODEC, FurnaceStateS2C::output,
                        ByteBufCodecs.VAR_INT, FurnaceStateS2C::progress,
                        ByteBufCodecs.VAR_INT, FurnaceStateS2C::burnTicks,
                        ByteBufCodecs.VAR_INT, FurnaceStateS2C::speed,
                        FurnaceStateS2C::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 一頁網盤快照（45 格）＋會員狀態 */
    public record CloudSnapshotS2C(int tier, long remainingMs, int page, List<ItemStack> slots)
            implements CustomPacketPayload {
        public static final Type<CloudSnapshotS2C> TYPE = payloadType("cloud_snapshot");
        public static final StreamCodec<RegistryFriendlyByteBuf, CloudSnapshotS2C> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, CloudSnapshotS2C::tier,
                        ByteBufCodecs.VAR_LONG, CloudSnapshotS2C::remainingMs,
                        ByteBufCodecs.VAR_INT, CloudSnapshotS2C::page,
                        ITEM_LIST, CloudSnapshotS2C::slots,
                        CloudSnapshotS2C::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
