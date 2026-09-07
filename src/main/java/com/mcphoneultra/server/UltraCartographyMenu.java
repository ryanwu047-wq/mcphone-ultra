package com.mcphoneultra.server;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;

/**
 * 隨身製圖台：複用原版製圖台介面，stillValid 永遠 true（手機遠程使用）。
 */
public final class UltraCartographyMenu extends CartographyTableMenu {

    public UltraCartographyMenu(int containerId, Inventory playerInventory,
                                ContainerLevelAccess access) {
        super(containerId, playerInventory, access);
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
