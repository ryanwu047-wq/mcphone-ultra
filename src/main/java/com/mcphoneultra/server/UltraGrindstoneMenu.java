package com.mcphoneultra.server;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.GrindstoneMenu;

/**
 * 隨身砂輪：複用原版砂輪介面，stillValid 永遠 true（手機遠程使用）。
 */
public final class UltraGrindstoneMenu extends GrindstoneMenu {

    public UltraGrindstoneMenu(int containerId, Inventory playerInventory,
                               ContainerLevelAccess access) {
        super(containerId, playerInventory, access);
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
