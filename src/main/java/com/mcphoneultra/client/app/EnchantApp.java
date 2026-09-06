package com.mcphoneultra.client.app;

import com.mcphoneultra.client.net.CloudPackets;
import com.november.mcphone.api.client.app.IPhoneApp;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/** ✨ 隨身附魔台：等效書架由網盤會員等級決定（VIP1=5、VIP2=10、VIP3 以上＝滿 15）。 */
public final class EnchantApp implements IPhoneApp {

    private static final String ID = "enchant";

    @Override
    public ResourceLocation getId() {
        return ResourceLocation.fromNamespaceAndPath("mcphone_ultra", ID);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("mcphone_ultra.app." + ID);
    }

    @Override
    public ResourceLocation getIconTexture() {
        return ResourceLocation.fromNamespaceAndPath("mcphone_ultra", "textures/app/" + ID + ".png");
    }

    @Override
    public void onPress() {
        PacketDistributor.sendToServer(new CloudPackets.EnchantOpenC2S());
    }

    @Override
    public boolean isPreinstalled() {
        return false;
    }

    @Override
    public String getAuthor() {
        return "Doubao";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return Component.translatable("mcphone_ultra.app." + ID + ".desc").getString();
    }
}
