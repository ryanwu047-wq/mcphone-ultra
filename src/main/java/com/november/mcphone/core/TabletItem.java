package com.november.mcphone.core;

import com.november.mcphone.core.client.PhoneScreenOpener;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 平板 —— 手机的放大版。屏幕解析度更大（直向 190×300 / 横向 320×190），
 * 界面组件全自动跟着变大；合成比手机难得多，是后期升级目标。
 */
public class TabletItem extends PhoneItem {

    public TabletItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            PhoneScreenOpener.openTablet(new PhoneLocation.InHand(hand), false);
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    @Override
    public Component getName(ItemStack stack) {
        String deviceName = stack.get(ModDataComponents.DEVICE_NAME.get());
        if (deviceName != null && !deviceName.isBlank()) {
            return Component.literal(deviceName);
        }
        return Component.translatable("item.mcphone.tablet");
    }
}
