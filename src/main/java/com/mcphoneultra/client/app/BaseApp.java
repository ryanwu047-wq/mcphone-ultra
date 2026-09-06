package com.mcphoneultra.client.app;

import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.api.client.ui.IPhonePage;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 所有 Ultra App 的公共基类：id / 名称 / 图标 / 描述都走语言文件，
 * 覆盖 openPage() 把页面画在手机里。只实现接口，不碰 MCphone 内部类。
 */
public abstract class BaseApp implements IPhoneApp {

    private final String id;
    private final boolean preinstalled;

    protected BaseApp(String id, boolean preinstalled) {
        this.id = id;
        this.preinstalled = preinstalled;
    }

    @Override
    public ResourceLocation getId() {
        return ResourceLocation.fromNamespaceAndPath("mcphone_ultra", id);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("mcphone_ultra.app." + id);
    }

    @Override
    public ResourceLocation getIconTexture() {
        return ResourceLocation.fromNamespaceAndPath("mcphone_ultra", "textures/app/" + id + ".png");
    }

    @Override
    public IPhonePage openPage() {
        return createPage();
    }

    /** 页面在这里建。每帧状态都在页面实例里，页面由 MCphone 管理生命周期。 */
    protected abstract IPhonePage createPage();

    @Override
    public void onPress() {
        // 覆盖了 openPage()，旧版 MCphone 上不会走到这里
    }

    @Override
    public boolean isPreinstalled() {
        return preinstalled;
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
        return Component.translatable("mcphone_ultra.app." + id + ".desc").getString();
    }
}
