package com.mcphoneultra.server.store;

import com.november.mcphone.api.cost.IAppPriceProvider;
import com.november.mcphone.api.cost.ICost;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ultra App 商店报价：给商店里可下载的 App 分配不同的鑽石价格。
 *
 * <p>注意：本类必须保持「服务端可加载」——报价由 {@code AppPriceRegistry} 在
 * 服务端与客户端同时读取（服务端负责真扣款），所以放在非 client 包、
 * 且不得引用任何 {@code net.minecraft.client.*} 类型。
 *
 * <p>价格以鑽石计：複製 64 鑽石，其余按功能梯度分配不同價格。
 * 修改价格直接改这里的数字即可。
 */
public final class UltraAppPrices implements IAppPriceProvider {

    private static ResourceLocation ultra(String path) {
        return ResourceLocation.fromNamespaceAndPath("mcphone_ultra", path);
    }

    @Override
    public Map<ResourceLocation, ICost> prices() {
        Map<ResourceLocation, ICost> out = new LinkedHashMap<>();

        // 複製 App：複製副手物品（含 NBT，≤5KB）——每次複製消耗 64 鑽石
        out.put(ultra("copy"), ICost.of(Items.DIAMOND, 64));

        // 商店其他可下载 App：不同 App 不同價格（鑽石）
        out.put(ultra("phone"), ICost.of(Items.DIAMOND, 48));       // 語音群組通話
        out.put(ultra("mailbox"), ICost.of(Items.DIAMOND, 32));     // 好友寄信與物品
        out.put(ultra("starshooter"), ICost.of(Items.DIAMOND, 32)); // STG 小遊戲
        out.put(ultra("memory"), ICost.of(Items.DIAMOND, 24));      // 記憶配對
        out.put(ultra("tetris"), ICost.of(Items.DIAMOND, 24));      // 下落方塊
        out.put(ultra("alarm"), ICost.of(Items.DIAMOND, 16));       // 真實時間提醒
        out.put(ultra("flashlight"), ICost.of(Items.DIAMOND, 16));  // 手電筒
        out.put(ultra("splash"), ICost.of(Items.DIAMOND, 8));       // 開機問候卡
        out.put(ultra("lucky"), ICost.of(Items.DIAMOND, 8));        // 搖一搖骰子

        return out;
    }
}
