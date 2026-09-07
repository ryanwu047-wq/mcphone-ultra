package com.november.mcphone.feature.settings.client;

import com.mcphoneultra.client.util.UltraDisplay;
import com.november.mcphone.core.client.ClientConfig;
import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.PhoneTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 顯示輔助設定頁：亮度滑桿（mcphone-ultra 內建）。
 * 從「設定」點進來，改完即時生效，按返回回到設定列表。
 */
public final class UltraDisplayPage {

    private static final int PAD_X = 6;

    /** 亮度滑桿的螢幕座標（滑桿拖動時複算，因為機身會移動） */
    private int barLeft, barTop, barWidth;

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, Font font) {

        final int x = phoneLeft + PAD_X;
        final int w = screenW - PAD_X * 2;
        final int bottom = phoneTop + screenH - navH;
        int y = phoneTop + statusH + 2;

        g.drawString(font, Component.translatable("mcphone_ultra.settings.display").getString(),
                x, y, FontPalette.title(), true);
        y += font.lineHeight;
        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        y += 3;

        // —— 亮度 ——
        g.drawString(font, "🔆 亮度：" + UltraDisplay.brightnessLabel(),
                x + 2, y, FontPalette.body(), false);
        y += font.lineHeight + 2;

        barLeft = x + 2;
        barTop = y;
        barWidth = w - 4;
        g.fill(barLeft, barTop, barLeft + barWidth, barTop + 7, PhoneTheme.COLOR_SLOT_BG);
        int fill = Math.round(barWidth * UltraDisplay.brightness());
        if (fill > 0) {
            g.fill(barLeft, barTop, barLeft + fill, barTop + 7, PhoneTheme.COLOR_MUSIC_PROGRESS);
        }
        // 滑桿把手
        g.fill(barLeft + fill - 1, barTop - 2, barLeft + fill + 2, barTop + 9,
                FontPalette.title());

        // 滑鼠在滑桿上＝即時調整
        if (mouseX >= barLeft && mouseX <= barLeft + barWidth
                && mouseY >= barTop - 3 && mouseY <= barTop + 10) {
            applyBrightness(mouseX);
        }

        y += 18;

        g.drawString(font, "即時生效，存在本機 config；返回鍵（◀）離開。",
                x + 2, y, FontPalette.subtle(), false);
    }

    private void applyBrightness(double mouseX) {
        float v = (float) Math.clamp((mouseX - barLeft) / (double) barWidth, 0.0, 1.0);
        UltraDisplay.set(v);
        ClientConfig.saveBrightness(v);
    }

    /** 滑桿在 render 裡即時處理；這裡一律吞掉點擊，返回交給導航欄 */
    public boolean mouseClicked(double mx, double my, int button) {
        return true;
    }

    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (button != 0) return false;
        if (mx >= barLeft && mx <= barLeft + barWidth
                && my >= barTop - 4 && my <= barTop + 12) {
            applyBrightness(mx);
            return true;
        }
        return false;
    }

    /** 滾輪微調亮度 */
    public boolean mouseScrolled(double scrollY) {
        if (scrollY == 0) return false;
        float v = Math.clamp(UltraDisplay.brightness() + (scrollY > 0 ? 0.05f : -0.05f), 0f, 1f);
        applyBrightness(barLeft + barWidth * v);
        return true;
    }
}
