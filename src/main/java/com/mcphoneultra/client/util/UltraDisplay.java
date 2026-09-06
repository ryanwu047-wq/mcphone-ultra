package com.mcphoneultra.client.util;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 顯示輔助（亮度＋色盲補助）的渲染狀態。
 *
 * 數值在配置載入時推進來、渲染每幀直接讀靜態欄位，不碰 ModConfigSpec——
 * 與 mcphone 的 FontPalette 同一套路，避免每幀 get() 的開銷與未載入異常。
 */
public final class UltraDisplay {

    public enum ColorBlindMode {
        NONE("關閉"),
        PROTANOPIA("紅色弱（Protanopia）"),
        DEUTERANOPIA("綠色弱（Deuteranopia）"),
        TRITANOPIA("藍黃弱（Tritanopia）"),
        MONOCHROME("全色盲（灰階）");

        public final String label;

        ColorBlindMode(String label) {
            this.label = label;
        }
    }

    /** 1.0 = 100% 亮度。渲染時蓋一層黑色，越暗越深 */
    private static volatile float brightness = 1f;

    private static volatile ColorBlindMode colorBlind = ColorBlindMode.NONE;

    private UltraDisplay() {}

    public static void set(float brightnessPercent, ColorBlindMode mode) {
        brightness = Math.clamp(brightnessPercent, 0f, 1f);
        colorBlind = mode == null ? ColorBlindMode.NONE : mode;
    }

    public static float brightness() {
        return brightness;
    }

    public static ColorBlindMode colorBlind() {
        return colorBlind;
    }

    public static String brightnessLabel() {
        return Math.round(brightness * 100) + "%";
    }

    public static String colorBlindLabel() {
        return colorBlind.label;
    }

    /**
     * 蓋在手機畫面上的一層濾鏡：
     * 亮度 → 半透明黑；色盲補助 → 對應通道的補償色遮罩。
     * 放在 nav bar 之後、機殼之前，因此只影響螢幕內容。
     */
    public static void renderOverlay(GuiGraphics g, int x, int y, int w, int h) {
        int alpha = (int) ((1f - brightness) * 255f);
        if (alpha > 0) {
            g.fill(x, y, x + w, y + h, (Math.min(alpha, 200) << 24) | 0x000000);
        }
        if (colorBlind != ColorBlindMode.NONE) {
            switch (colorBlind) {
                case PROTANOPIA -> g.fill(x, y, x + w, y + h, 0x14 << 24 | 0x00D8FF);   // 補紅：疊淡青色
                case DEUTERANOPIA -> g.fill(x, y, x + w, y + h, 0x14 << 24 | 0xFF50B0); // 補綠：疊淡紫紅
                case TRITANOPIA -> g.fill(x, y, x + w, y + h, 0x14 << 24 | 0xFFD050);   // 補藍：疊淡琥珀
                // 灰階：真正的去飽和。GuiGraphics 沒有逐像素能力，用「先疊白沖淡、
                // 再疊黑壓暗」的雙層近似：最終每通道 ≈ src*0.29 + 40，色差被壓到
                // 約 1/3，彩色畫面變成明顯的灰白畫面。
                case MONOCHROME -> {
                    g.fill(x, y, x + w, y + h, 0x59FFFFFF);
                    g.fill(x, y, x + w, y + h, 0x8C000000);
                }
                case NONE -> { }
            }
        }
    }
}
