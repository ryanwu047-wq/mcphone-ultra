package com.mcphoneultra.server;

import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.EnchantmentMenu;

/**
 * 隨身附魔台：等效書架數由網盤會員等級決定（VIP1=5、VIP2=10、VIP3 以上＝15）。
 * 介面仍是原版附魔台（EnchantmentScreen 自動匹配），只是三檔等級按書架重算。
 */
public final class UltraEnchantMenu extends EnchantmentMenu {

    private final int books;

    public UltraEnchantMenu(int containerId, Inventory playerInventory,
                            ContainerLevelAccess access, int books) {
        super(containerId, playerInventory, access);
        this.books = books;
        if (books > 0) {
            this.enchantClue[0] = Mth.clamp(books * 2, 1, 30);
            this.enchantClue[1] = Mth.clamp(books * 2 - 1, 1, 30);
            this.enchantClue[2] = Mth.clamp(books * 2 - 2, 1, 30);
            this.costs[0] = this.enchantClue[0];
        this.levelClue[0] = this.enchantClue[0];
            this.costs[1] = this.enchantClue[1];
        this.levelClue[1] = this.enchantClue[1];
            this.costs[2] = this.enchantClue[2];
        this.levelClue[2] = this.enchantClue[2];
        }
    }

    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        if (books <= 0) return;
        this.enchantClue[0] = Mth.clamp(books * 2, 1, 30);
        this.enchantClue[1] = Mth.clamp(books * 2 - 1, 1, 30);
        this.enchantClue[2] = Mth.clamp(books * 2 - 2, 1, 30);
        this.costs[0] = this.enchantClue[0];
        this.levelClue[0] = this.enchantClue[0];
        this.costs[1] = this.enchantClue[1];
        this.levelClue[1] = this.enchantClue[1];
        this.costs[2] = this.enchantClue[2];
        this.levelClue[2] = this.enchantClue[2];
    }
}
