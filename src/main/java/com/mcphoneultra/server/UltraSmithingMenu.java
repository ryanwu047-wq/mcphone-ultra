package com.mcphoneultra.server;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SmithingMenu;

/**
 * 隨身鍛造台：介面完全複用原版鍛造台（SmithingMenu），只有 stillValid 永遠 true
 * ——原版檢查玩家與鍛造台方塊的距離（<8 格），手機裡開一秒就被關掉。
 */
public final class UltraSmithingMenu extends SmithingMenu {

    public UltraSmithingMenu(int containerId, Inventory playerInventory,
                             ContainerLevelAccess access) {
        super(containerId, playerInventory, access);
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
