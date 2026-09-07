package com.mcphoneultra.server;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;

/**
 * 隨身合成台：介面完全複用原版合成（CraftingMenu），只有 stillValid 改為
 * 永遠 true —— 原版會檢查玩家與合成台的距離（<8 格），手機裡開一秒就被
 * 關掉（玩家根本不站在合成台旁）。手機是合法使用場合，直接豁免距離檢查。
 */
public final class UltraCraftingMenu extends CraftingMenu {

    public UltraCraftingMenu(int containerId, Inventory playerInventory,
                             ContainerLevelAccess access) {
        super(containerId, playerInventory, access);
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
