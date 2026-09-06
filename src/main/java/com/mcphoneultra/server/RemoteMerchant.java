package com.mcphoneultra.server;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import javax.annotation.Nullable;

/**
 * 遠程交易用的商人包裝：把「正在交易」的狀態存在自己身上，不綁定到真村民。
 * <p>
 * 原版村民實體每 tick 檢查「交易玩家距離 &gt; 8 格就 closeContainer()」，
 * 遠程交易一開就會被村民自己踢掉（介面閃退）。把 tradingPlayer 存在這個包裝上，
 * 真村民永遠不知道有人在交易，距離檢查不會觸發；offers／升級／經驗／音效
 * 全部委派給真村民，交易與升級照常生效。
 */
public final class RemoteMerchant implements Merchant {

    private final Villager delegate;
    @Nullable
    private Player tradingPlayer;

    public RemoteMerchant(Villager delegate) {
        this.delegate = delegate;
    }

    @Override
    public void setTradingPlayer(@Nullable Player player) {
        this.tradingPlayer = player;
    }

    @Override
    @Nullable
    public Player getTradingPlayer() {
        return this.tradingPlayer;
    }

    @Override
    public MerchantOffers getOffers() {
        return this.delegate.getOffers();
    }

    @Override
    public void overrideOffers(MerchantOffers offers) {
        this.delegate.overrideOffers(offers);
    }

    @Override
    public void notifyTrade(MerchantOffer offer) {
        this.delegate.notifyTrade(offer);
    }

    @Override
    public void notifyTradeUpdated(ItemStack stack) {
        this.delegate.notifyTradeUpdated(stack);
    }

    @Override
    public int getVillagerXp() {
        return this.delegate.getVillagerXp();
    }

    @Override
    public void overrideXp(int xp) {
        this.delegate.overrideXp(xp);
    }

    @Override
    public boolean showProgressBar() {
        return this.delegate.showProgressBar();
    }

    @Override
    public SoundEvent getNotifyTradeSound() {
        return this.delegate.getNotifyTradeSound();
    }

    @Override
    public boolean canRestock() {
        return this.delegate.canRestock();
    }

    @Override
    public boolean isClientSide() {
        return false;
    }

    /** 村民目前的等級（用於 offers 封包的 level 欄位） */
    public int getLevel() {
        return this.delegate.getVillagerData().getLevel();
    }
}
