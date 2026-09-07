package com.november.mcphone.feature.camera.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mcphoneultra.client.net.CloudPackets;
import com.november.mcphone.MCphone;
import com.november.mcphone.core.client.MCphoneKeyBindings;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.function.Consumer;

/**
 * 相机模式的事件监听。三个事件都在游戏总线（NeoForge.EVENT_BUS），由 MCphoneClient 显式 addListener；
 * 按键的注册在模组总线，见 MCphoneKeyBindings。拍照的分帧时序见 {@link CameraMode} 的类注释。
 *
 * Ultra 扩展：V 键切换自拍（第三人称背后视角，玩家自己调整），拍照走原版截图落盘。
 */
public final class CameraHandler {

    private CameraHandler() {}

    private static boolean selfieKeyDown = false;
    private static boolean mapKeyDown = false;

    public static void onClientTick(ClientTickEvent.Post event) {
        if (!CameraMode.isActive()) return;

        Minecraft mc = Minecraft.getInstance();

        // 上一帧已是不含取景框的干净画面，可以抓取了
        if (CameraMode.shouldGrabNow()) {
            CameraMode.finishCapture();
            grab(mc, msg -> {
                if (mc.player != null) mc.player.displayClientMessage(msg, true);
            });
        }

        // 自拍切换：边缘触发，按住不会连跳
        long win = mc.getWindow().getWindow();
        boolean vDown = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_V) == GLFW.GLFW_PRESS;
        if (vDown && !selfieKeyDown) CameraMode.toggleSelfie();
        selfieKeyDown = vDown;

        // G 键：把当前画面导出成满级地图画（不落盘 png，直接转地图）
        boolean gDown = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_G) == GLFW.GLFW_PRESS;
        if (gDown && !mapKeyDown) exportMap(mc);
        mapKeyDown = gDown;

        // 退出优先于拍照：同一 tick 内两键同时按下时以退出为准
        boolean exitPressed = false;
        while (MCphoneKeyBindings.CAMERA_EXIT.consumeClick()) exitPressed = true;
        if (exitPressed) {
            CameraMode.exit();
            return;
        }

        while (MCphoneKeyBindings.CAMERA_SHUTTER.consumeClick()) {
            CameraMode.requestCapture();
        }
    }

    /** 抓当前画面 → 缩到 128×128 → 对应地图调色板 → 送服务端写进满级地图 */
    private static void exportMap(Minecraft mc) {
        if (mc.player == null) return;
        NativeImage img;
        try {
            img = Screenshot.takeScreenshot(mc.getMainRenderTarget());
        } catch (Exception e) {
            MCphone.LOGGER.error("导出地图画失败：截图失败", e);
            return;
        }
        try (NativeImage im = img) {
            byte[] colors = toMapColors(im);
            PacketDistributor.sendToServer(new CloudPackets.MapArtUploadC2S(colors));
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("已导出地图画（满级地图），已放入背包"), true);
            }
        } catch (Exception e) {
            MCphone.LOGGER.error("导出地图画失败", e);
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("导出地图画失败：" + e.getMessage()), true);
            }
        }
    }

    /** 缩放并把每个像素映射到最近的地图颜色（0-63 索引） */
    private static byte[] toMapColors(NativeImage img) {
        int[] base = mapColorBase();
        if (base == null) return new byte[128 * 128];
        byte[] out = new byte[128 * 128];
        try (NativeImage small = new NativeImage(128, 128, true)) {
            img.resizeSubRectTo(0, 0, img.getWidth(), img.getHeight(), small);
            for (int y = 0; y < 128; y++) {
                for (int x = 0; x < 128; x++) {
                    int abgr = small.getPixelRGBA(x, y);
                    int r = abgr & 0xFF;
                    int g = (abgr >> 8) & 0xFF;
                    int b = (abgr >> 16) & 0xFF;
                    int best = 0;
                    int bestD = Integer.MAX_VALUE;
                    for (int i = 0; i < 64; i++) {
                        int c = base[i];
                        int dr = r - ((c >> 16) & 0xFF);
                        int dg = g - ((c >> 8) & 0xFF);
                        int db = b - (c & 0xFF);
                        int d = dr * dr * 30 + dg * dg * 59 + db * db * 11;
                        if (d < bestD) {
                            bestD = d;
                            best = i;
                        }
                    }
                    out[y * 128 + x] = (byte) best;
                }
            }
        }
        return out;
    }

    /** MATERIAL_COLORS 是 private，反射取 64 色地圖調色板；取不到回 null */
    private static int[] mapColorBase() {
        try {
            java.lang.reflect.Field f = MapColor.class.getDeclaredField("MATERIAL_COLORS");
            f.setAccessible(true);
            MapColor[] palette = (MapColor[]) f.get(null);
            if (palette == null || palette.length != 64) return null;
            int[] base = new int[64];
            for (int i = 0; i < 64; i++) {
                base[i] = palette[i].col;
            }
            return base;
        } catch (Exception e) {
            MCphone.LOGGER.error("讀取地圖調色板失敗", e);
            return null;
        }
    }

    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!CameraMode.isActive()) return;

        // 拍照期间必须跳过取景框，否则会被拍进照片
        if (CameraMode.suppressOverlay()) {
            CameraMode.markCleanFrame();
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        CameraOverlay.render(
                event.getGuiGraphics(),
                mc.font,
                mc.getWindow().getGuiScaledWidth(),
                mc.getWindow().getGuiScaledHeight(),
                System.currentTimeMillis());
    }

    /** 安全网：打开任意界面就退出相机模式，否则玩家会卡在没有 HUD 的状态里 */
    public static void onScreenOpening(ScreenEvent.Opening event) {
        CameraMode.exit();
    }

    /** 抓主渲染目标，IO 线程落盘（等价原版 F2） */
    private static void grab(Minecraft mc, Consumer<Component> cb) {
        NativeImage img = Screenshot.takeScreenshot(mc.getMainRenderTarget());
        File dir = new File(mc.gameDirectory, "screenshots");
        if (!dir.exists()) dir.mkdirs();
        File target = uniqueName(dir);
        final boolean selfie = CameraMode.selfie;

        Util.ioPool().execute(() -> {
            try (NativeImage im = img) {
                im.writeToFile(target);
                cb.accept(Component.literal("已保存 " + target.getName() + (selfie ? "（自拍）" : "")));
            } catch (Exception e) {
                MCphone.LOGGER.error("保存照片失败", e);
                cb.accept(Component.literal("保存失败：" + e.getMessage()));
            }
        });
    }

    /** 与原版同格式的时间戳文件名，重名时追加 _1、_2 */
    private static File uniqueName(File dir) {
        String base = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date());
        for (int i = 1; ; i++) {
            File f = new File(dir, base + (i == 1 ? "" : "_" + i) + ".png");
            if (!f.exists()) return f;
        }
    }
}
