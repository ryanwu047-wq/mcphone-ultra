package com.november.mcphone.feature.settings.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.api.client.app.RequiredMod;
import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.core.client.PhoneScreenRegistry;
import com.november.mcphone.core.client.PhoneTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;

import java.util.List;
import java.util.function.Supplier;

/**
 * 一个 App 的管理页 —— 它是谁、谁给的、以及能对它做什么。
 *
 * 为什么要有这一页
 *
 * 在它之前，App 管理器是一个平列表，【点一行就卸载】。那有两个毛病：玩家点开这一页
 * 多半是想看看某个 App 是什么来路，结果手一抖就把它卸了；而删一张照片反倒是要点两次的
 * （见 Gallery 的删除键），同一部手机里两套规矩。
 *
 * 更要紧的是往后：每个 App 迟早要有自己的开关。没有"一个 App 一页"这个地方，那些开关
 * 只能往列表行上挤，或者散到各自的 App 里去——前者那一行只有 108 像素宽，后者等于没有
 * 统一的入口。所以先把这一页立起来，操作区做成一行一个，以后加开关就是加一行。
 *
 * 这一版的操作只有卸载
 *
 * 安装留在应用商店：那一页管的是"有价钱的、远程来源的"，而这里只列已经装上的。
 * 系统 App 的卸载键是灰的，并写明为什么——不写的话玩家会以为是坏了。
 *
 * 读第三方 App 的元数据一律兜住
 *
 * getVersion / getAuthor / getDescription 都是附属实现的，抛什么全凭它们高兴。
 * 这一页的全部内容都来自这些方法，不兜的话一个坏附属能让整页画不出来——而玩家
 * 恰恰是为了搞清楚"这个 App 有什么毛病"才点进来的。见 {@link #safe}。
 */
public final class AppManagerDetail {

    private static final int PAD = 6;
    private static final int BIG_ICON = 32;
    private static final int BUTTON_H = 16;

    private IPhoneApp app;

    /** 卸载键：第一次点上膛，第二次才真卸。与相册删照片同一条规矩 */
    private boolean uninstallArmed;

    /** 渲染时算出来，点击时复用 */
    private int btnX, btnY, btnW;
    private boolean btnHovered;

    /** 卸载完了，请求退回列表页，等 PhoneScreen 来取 */
    private boolean backRequest;

    /**
     * 正文往上滚了多少像素。
     *
     * 描述是附属自己写的，长度不由我们定；再加上前置与联动各占一行，正文放不下是常态。
     * 原来放不下就直接不画（{@code drawInfoLine} 里那句提前 return），玩家看不到自己
     * 缺哪个前置 —— 而那正是他点进这一页要找的答案。
     */
    private int scrollPx;

    /** 上一帧量出来的滚动上限，正文有多高只有画完才知道 */
    private int maxScroll;

    public void open(IPhoneApp target) {
        this.app = target;
        this.uninstallArmed = false;
        this.btnHovered = false;
        this.backRequest = false;
        this.scrollPx = 0;
        this.maxScroll = 0;
    }

    public void close() {
        this.app = null;
        this.uninstallArmed = false;
    }

    public boolean consumeBackRequest() {
        boolean r = backRequest;
        backRequest = false;
        return r;
    }

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, float partialTick, Font font) {

        int x = phoneLeft + PAD;
        int y = phoneTop + statusH + 4;
        int w = screenW - PAD * 2;
        int bottom = phoneTop + screenH - navH;

        if (app == null) {
            g.drawString(font, Component.translatable("mcphone.gui.app_manager_empty").getString(),
                    x, y, FontPalette.subtle(), false);
            return;
        }

        //  头部：图标 + 名字 + 作者·版本 
        // 用 App 自己的 renderIcon 而不是直接画贴图：图标可以是自己画的、甚至是动的
        final int iconX = x;
        final int iconY = y;
        safeRun(() -> app.renderIcon(g, iconX, iconY, BIG_ICON, partialTick));

        int textX = x + BIG_ICON + 5;
        int textW = w - BIG_ICON - 5;

        String name = safe(() -> app.getDisplayName().getString(), app.getId().toString());
        g.drawString(font, GuiUtil.truncate(font, name, textW), textX, y + 2,
                FontPalette.title(), false);

        String author = safe(app::getAuthor, "");
        String version = safe(app::getVersion, "");
        String meta = author.isBlank() ? "v" + version : author + " · v" + version;
        g.drawString(font, GuiUtil.truncate(font, meta, textW),
                textX, y + 2 + font.lineHeight + 2, FontPalette.subtle(), false);

        y += BIG_ICON + 6;
        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        y += 4;

        //  正文：描述 + 由谁提供 + 前置/联动 
        // 操作区的位置先扣出来，正文只能画到它上面为止
        final int bodyBottom = bottom - BUTTON_H - font.lineHeight - 8;
        final int bodyTop = y;

        scrollPx = Math.clamp(scrollPx, 0, maxScroll);
        y -= scrollPx;

        // 越界的部分交给 scissor 裁，不再"放不下就不画"——那样卸载键上方会凭空少几行
        g.enableScissor(x, bodyTop, x + w, bodyBottom);

        String desc = safe(app::getDescription, "");
        if (desc.isBlank()) desc = Component.translatable("mcphone.store.no_description").getString();
        for (var line : font.split(Component.literal(desc), w)) {
            g.drawString(font, line, x, y, FontPalette.body(), false);
            y += font.lineHeight + 1;
        }

        y += 3;
        y = drawInfoLine(g, font, x, y, w,
                Component.translatable("mcphone.gui.app_provider").getString(), providerName());

        for (RequiredMod required : PhoneScreenRegistry.requiredModsOf(app)) {
            y = drawModLine(g, font, x, y, w, "mcphone.gui.app_requires", required);
        }
        for (RequiredMod companion : PhoneScreenRegistry.companionModsOf(app)) {
            y = drawModLine(g, font, x, y, w, "mcphone.gui.app_companion", companion);
        }

        g.disableScissor();

        maxScroll = Math.max(0, (y + scrollPx) - bodyBottom);

        //  操作区：这一版只有卸载 
        renderUninstallButton(g, font, x, bottom, w, mouseX, mouseY);
    }

    /** 滚轮翻正文。头部与卸载键不跟着滚：那个键得一直够得着 */
    public boolean mouseScrolled(double scrollY, Font font) {
        int before = scrollPx;
        scrollPx = Math.clamp(scrollPx - (int) (scrollY * font.lineHeight * 3), 0, maxScroll);
        return scrollPx != before;
    }

    /** 「标签：值」一行。越界由调用方的 scissor 裁，这里只管画 */
    private static int drawInfoLine(GuiGraphics g, Font font, int x, int y, int w,
                                    String label, String value) {
        g.drawString(font, GuiUtil.truncate(font, label + " " + value, w), x, y,
                FontPalette.subtle(), false);
        return y + font.lineHeight + 1;
    }

    /** 前置 / 联动那几行：模组名 + 装没装 */
    private static int drawModLine(GuiGraphics g, Font font, int x, int y, int w,
                                   String labelKey, RequiredMod mod) {
        boolean loaded = ModList.get().isLoaded(mod.modId());
        String label = Component.translatable(labelKey).getString() + " " + mod.displayName();
        String mark = Component.translatable(loaded
                ? "mcphone.gui.app_mod_present" : "mcphone.gui.app_mod_absent").getString();

        g.drawString(font, GuiUtil.truncate(font, label, w - font.width(mark) - 4), x, y,
                FontPalette.subtle(), false);
        g.drawString(font, mark, x + w - font.width(mark), y,
                loaded ? FontPalette.confirm() : FontPalette.danger(), false);
        return y + font.lineHeight + 1;
    }

    /**
     * 卸载键。系统 App 画成灰的、点不动，下面写一行为什么。
     *
     * 上膛之后字变成「再点一次确认」，颜色也换——玩家得看得出来这一下与上一下不是同一件事。
     */
    private void renderUninstallButton(GuiGraphics g, Font font, int x, int bottom, int w,
                                       int mouseX, int mouseY) {

        boolean system = app.isSystemApp();

        btnX = x;
        btnW = w;
        btnY = bottom - BUTTON_H - font.lineHeight - 2;
        btnHovered = !system && GuiUtil.hit(mouseX, mouseY, btnX, btnY, btnW, BUTTON_H);

        int bg = system ? PhoneTheme.COLOR_BUTTON_DISABLED
                : (btnHovered ? PhoneTheme.COLOR_ROW_HOVER_DANGER : PhoneTheme.COLOR_ROW_HOVER);
        g.fill(btnX, btnY, btnX + btnW, btnY + BUTTON_H, bg);

        String label = Component.translatable(uninstallArmed
                ? "mcphone.gui.uninstall_confirm" : "mcphone.gui.uninstall").getString();
        int color = system ? FontPalette.dim()
                : (uninstallArmed ? FontPalette.dangerArmed() : FontPalette.uninstall());
        g.drawString(font, label, btnX + (btnW - font.width(label)) / 2,
                btnY + (BUTTON_H - font.lineHeight) / 2, color, false);

        if (system) {
            String why = Component.translatable("mcphone.gui.system_app_locked").getString();
            g.drawString(font, GuiUtil.truncate(font, why, w),
                    x, btnY + BUTTON_H + 2, FontPalette.dim(), false);
        }
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || app == null) return true;

        if (!btnHovered) {
            // 点别处＝把上膛的卸载卸下来。与相册的删除键同一条：走开就等于反悔
            uninstallArmed = false;
            return true;
        }
        if (app.isSystemApp()) return true;

        if (!uninstallArmed) {
            uninstallArmed = true;
            return true;
        }

        PhoneScreenRegistry.uninstall(app.getId());
        uninstallArmed = false;
        backRequest = true;      // 这个 App 已经不在列表里了，留在它的详情页上没有意义
        return true;
    }

    /** 这个 App 是哪个模组给的：按 id 的命名空间查，查不到就把命名空间本身显示出来 */
    private String providerName() {
        String namespace = app.getId().getNamespace();
        return ModList.get().getModContainerById(namespace)
                .map(c -> c.getModInfo().getDisplayName())
                .orElse(namespace);
    }

    /**
     * 读一个第三方 App 的字符串，抛了就用兜底值。
     *
     * 连 Throwable 一起接：不可用的 App（前置没装）读它的方法会抛 NoClassDefFoundError，
     * 那不是 Exception。PhoneScreenRegistry.requiredModsOf 里已经踩过同一个坑。
     */
    private static String safe(Supplier<String> getter, String fallback) {
        try {
            String value = getter.get();
            return value == null ? fallback : value;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static void safeRun(Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            MCphone.LOGGER.warn("[MCphone] App 管理页里画图标失败: {}", t.toString());
        }
    }
}
