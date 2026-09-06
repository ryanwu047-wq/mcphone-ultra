package com.mcphoneultra.client.villager;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 綁定村民：蹲下＋右鍵村民 → 記入手機聯絡人（files/villagers/binds.txt）。
 * 純客戶端動作；蹲下右鍵在原版不會開交易介面，所以不與正常交易衝突。
 */
public final class VillagerBind {

    private VillagerBind() {}

    /** 由 MCphone 構造掛到遊戲總線 */
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof LocalPlayer player)) return;
        if (!player.isShiftKeyDown()) return;
        if (!(event.getTarget() instanceof Villager villager)) return;

        VillagerData.bind(villager);
        player.displayClientMessage(
                Component.literal("📇 已綁定村民「" + villager.getName().getString()
                        + "」職業 " + professionName(villager)), true);
    }

    public static String professionName(Villager v) {
        return Component.translatable(
                        "entity.minecraft.villager." + v.getVillagerData().getProfession().name().toLowerCase())
                .getString();
    }
}
