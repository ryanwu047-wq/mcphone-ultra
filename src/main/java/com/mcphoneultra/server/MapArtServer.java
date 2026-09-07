package com.mcphoneultra.server;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 地圖畫：把照片（128×128 地圖顏色索引）寫進一張滿級（scale 4）地圖。
 * 玩家拿到的是一張可掛畫框、可展示的真地圖。
 */
public final class MapArtServer {

    private MapArtServer() {
    }

    public static void giveMap(ServerPlayer p, byte[] colors) {
        if (colors == null || colors.length != 128 * 128) return;
        ServerLevel level = p.serverLevel();

        // 滿級地圖：scale 4 是最大縮放（8 張紙合成到頂）
        ItemStack map = MapItem.create(level, p.getBlockX(), p.getBlockZ(), (byte) 4, true, false);
        MapItemSavedData data = MapItem.getSavedData(map, level);
        if (data == null) return;

        // 直接把照片像素寫進地圖顏色陣列（0-63 = 地圖調色板索引）
        System.arraycopy(colors, 0, data.colors, 0, 128 * 128);

        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date());
        map.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal("照片地圖 " + stamp));

        if (!p.getInventory().add(map)) {
            p.drop(map, false);
        }
        p.sendSystemMessage(Component.literal("已獲得滿級照片地圖（128×128 地圖畫），可掛畫框展示"));
    }
}
