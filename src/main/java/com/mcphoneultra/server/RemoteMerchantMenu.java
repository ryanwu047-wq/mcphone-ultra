package com.mcphoneultra.server;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * 遠程村民交易選單：繼承原版 {@link MerchantMenu}，協定（MenuType.MERCHANT）、
 * 槽位座標、交易流程、SelectTrade 處理全沿用，只調整三點：
 * <ul>
 *   <li>{@link #stillValid} 永遠 true——遠程交易不檢查距離（ServerPlayer 每 tick 檢查，
 *       不合規就關閉介面）；</li>
 *   <li>結果槽 shift-click 禁用——原版 quickMoveStack 在 shift 拿結果時呼叫
 *       playTradeSound()，把 Merchant 直接 cast 成 Entity，我們的包裝不是實體會炸；</li>
 *   <li>offers 每次變化主動重發（交易一次後剩餘次數即時更新）。</li>
 * </ul>
 */
public final class RemoteMerchantMenu extends MerchantMenu {

    private final ServerPlayer owner;
    private final RemoteMerchant rm;
    private MerchantOffers lastSent = new MerchantOffers();

    public RemoteMerchantMenu(int containerId, Inventory playerInventory, RemoteMerchant trader) {
        super(containerId, playerInventory, trader);
        this.owner = (ServerPlayer) playerInventory.player;
        this.rm = trader;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index == 2) {
            // 遠程包裝不是 Entity，原版 playTradeSound 的 Entity cast 會炸；禁用 shift 拿結果
            return ItemStack.EMPTY;
        }
        return super.quickMoveStack(player, index);
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        MerchantOffers current = this.getOffers();
        if (!current.equals(this.lastSent)) {
            this.lastSent = new MerchantOffers();
            this.lastSent.addAll(current);
            this.owner.sendMerchantOffers(
                    this.containerId,
                    current,
                    this.rm.getLevel(),
                    this.rm.getVillagerXp(),
                    this.rm.showProgressBar(),
                    this.rm.canRestock());
        }
    }
}
