package com.mcphoneultra.client.util;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 顯示輔助（僅亮度）的渲染狀態。
 *
 * 數值在配置載入時推進來、渲染每幀直接讀靜態欄位，不碰 ModConfigSpec——
 * 與 mcphone 的 FontPalette 同一套路，避免每幀 get() 的開銷與未載入異常。
 */
public final class UltraDisplay {

    /** 1.0 = 100% 亮度。渲染時蓋一層黑色，越暗越深 */
    private static volatile float brightness = 1f;

    private UltraDisplay() {}

    public static void set(float brightnessPercent) {
        brightness = Math.clamp(brightnessPercent, 0f, 1f);
    }

    public static float brightness() {
        return brightness;
    }

    public static String brightnessLabel() {
        return Math.round(brightness * 100) + "%";
    }

    /**
     * 蓋在手機畫面上的一層黑色，越暗越深。
     * 放在 nav bar 之後、機殼之前，因此只影響螢幕內容。
     */
    public static void renderOverlay(GuiGraphics g, int x, int y, int w, int h) {
        int alpha = (int) ((1f - brightness) * 255f);
        if (alpha > 0) {
            g.fill(x, y, x + w, y + h, (Math.min(alpha, 200) << 24) | 0x000000);
        }
    }
}
