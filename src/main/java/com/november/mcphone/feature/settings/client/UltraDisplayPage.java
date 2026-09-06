package com.november.mcphone.feature.settings.client;

import com.mcphoneultra.client.util.UltraDisplay;
import com.november.mcphone.core.client.ClientConfig;
import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.PhoneTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 顯示輔助設定頁：亮度滑桿＋色盲補助選項（mcphone-ultra 內建）。
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
        int y = phoneTop + statusH + 4;

        g.drawString(font, Component.translatable("mcphone_ultra.settings.display").getString(),
                x, y, FontPalette.title(), true);
        y += font.lineHeight + 4;
        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        y += 4;

        // —— 亮度 ——
        g.drawString(font, "🔆 亮度：" + UltraDisplay.brightnessLabel(),
                x + 2, y, FontPalette.body(), false);
        y += font.lineHeight + 5;

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

        y += 16;

        // —— 色盲補助 ——
        g.drawString(font, "🎨 色盲補助（螢幕濾鏡）", x + 2, y, FontPalette.body(), false);
        y += font.lineHeight;
        g.drawString(font, "目前：" + UltraDisplay.colorBlindLabel(),
                x + 2, y, FontPalette.subtle(), false);
        y += font.lineHeight + 5;

        String[] ids = { "NONE", "PROTANOPIA", "DEUTERANOPIA", "TRITANOPIA", "MONOCHROME" };
        String[] names = { "關閉", "紅弱", "綠弱", "藍黃弱", "灰階" };
        int[] bg = {
                PhoneTheme.COLOR_BUTTON_DISABLED, 0xFF2A4A5A, 0xFF4A2A5A, 0xFF4A422A, 0xFF3A3A3A
        };

        int bw = (w - 4 * 3) / 5;
        for (int i = 0; i < 5; i++) {
            int bx = x + 2 + i * (bw + 3);
            boolean active = UltraDisplay.colorBlind().name().equals(ids[i]);
            if (mouseX >= bx && mouseX <= bx + bw && mouseY >= y && mouseY <= y + 18) {
                applyColorBlind(UltraDisplay.ColorBlindMode.valueOf(ids[i]));
            }
            g.fill(bx, y, bx + bw, y + 18, active ? PhoneTheme.COLOR_MUSIC_PROGRESS : bg[i]);
            String label = names[i];
            g.drawString(font, label, bx + (bw - font.width(label)) / 2, y + 5,
                    active ? FontPalette.title() : FontPalette.body(), false);
        }

        y += 26;

        // —— 預覽與說明 ——
        g.drawString(font, "預覽（濾鏡已套在整個手機畫面）",
                x + 2, y, FontPalette.subtle(), false);
        y += font.lineHeight + 3;
        g.fill(x + 2, y, x + 34, y + 16, 0xFFFF4444);
        g.fill(x + 38, y, x + 70, y + 16, 0xFF44FF44);
        g.fill(x + 74, y, x + 106, y + 16, 0xFF4488FF);
        g.fill(x + 110, y, x + 142, y + 16, 0xFFFFFF44);
        g.drawString(font, "紅 綠 藍 黃", x + 150, y + 4, FontPalette.body(), false);
        y += 24;

        g.drawString(font, "亮度與濾鏡即時生效，存在本機 config 裡。",
                x + 2, y, FontPalette.subtle(), false);
        y += font.lineHeight + 2;
        g.drawString(font, "返回鍵（◀）回到設定列表。",
                x + 2, y, FontPalette.subtle(), false);

        if (y > bottom) {
            g.drawString(font, "（畫面太小，滾輪可調亮度）", x + 2, bottom - 12,
                    FontPalette.subtle(), false);
        }
    }

    private void applyBrightness(double mouseX) {
        float v = (float) Math.clamp((mouseX - barLeft) / (double) barWidth, 0.0, 1.0);
        UltraDisplay.set(v, UltraDisplay.colorBlind());
        ClientConfig.saveBrightness(v);
    }

    private void applyColorBlind(UltraDisplay.ColorBlindMode mode) {
        UltraDisplay.set(UltraDisplay.brightness(), mode);
        ClientConfig.saveColorBlind(mode);
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
