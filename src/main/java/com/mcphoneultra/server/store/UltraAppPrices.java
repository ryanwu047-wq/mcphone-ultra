package com.mcphoneultra.server.store;

import com.november.mcphone.api.cost.IAppPriceProvider;
import com.november.mcphone.api.cost.ICost;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ultra App 商店报价：给商店里可下载的 App 分配不同的价格。
 *
 * <p>注意：本类必须保持「服务端可加载」——报价由 {@code AppPriceRegistry} 在
 * 服务端与客户端同时读取（服务端负责真扣款），所以放在非 client 包、
 * 且不得引用任何 {@code net.minecraft.client.*} 类型。
 *
 * <p>价格不只用鑽石：複製 64 鑽石，其余 App 按功能梯度混用不同物品
 * （绿宝石 / 金锭 / 铁锭 / 末影珍珠）。修改价格直接改这里的数字即可。
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

        // 商店其他可下载 App：不同 App 不同价格，混用不同物品（鑽石 / 绿宝石 / 金锭 / 铁锭 / 末影珍珠）
        out.put(ultra("phone"), ICost.of(Items.EMERALD, 24));       // 語音群組通話 —— 24 绿宝石
        out.put(ultra("mailbox"), ICost.of(Items.EMERALD, 16));     // 好友寄信與物品 —— 16 绿宝石
        out.put(ultra("starshooter"), ICost.of(Items.GOLD_INGOT, 8));   // STG 小遊戲 —— 8 金锭
        out.put(ultra("memory"), ICost.of(Items.ENDER_PEARL, 8));   // 記憶配對 —— 8 末影珍珠
        out.put(ultra("tetris"), ICost.of(Items.GOLD_INGOT, 4));    // 下落方塊 —— 4 金锭
        out.put(ultra("alarm"), ICost.of(Items.IRON_INGOT, 8));     // 真實時間提醒 —— 8 铁锭
        out.put(ultra("flashlight"), ICost.of(Items.IRON_INGOT, 4));    // 手電筒 —— 4 铁锭
        out.put(ultra("splash"), ICost.of(Items.EMERALD, 2));       // 開機問候卡 —— 2 绿宝石
        out.put(ultra("lucky"), ICost.of(Items.GOLD_INGOT, 2));     // 搖一搖骰子 —— 2 金锭

        return out;
    }
}
