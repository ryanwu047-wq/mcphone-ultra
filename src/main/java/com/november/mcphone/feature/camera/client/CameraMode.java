package com.november.mcphone.feature.camera.client;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

/**
 * 相机模式状态机（纯客户端）。相机不是 GUI，是覆盖在游戏画面上的模式。
 *
 * 拍照要分帧，否则取景框会被拍进照片：按键置 pendingCapture → 渲染帧跳过取景框并置
 * cleanFrameReady → 下一 tick 抓取（期间的每一帧都被抑制，抓到的必然干净）。
 * 抓取放在 tick 而不是渲染中：Screenshot.grab 会 bindTexture 动 RenderSystem，与原版 F2 时机一致。
 *
 * Ultra 扩展：自拍＝切到第三人称背后视角并【保持】，玩家自己用鼠标调整视角，
 * 调好了再按快门，拍到的就是当前视角（能看到自己）。关闭自拍时还原第一人称。
 */
public final class CameraMode {

    private static boolean active = false;

    /** 进入相机前玩家原本的 hideGui 设置，退出时还原 */
    private static boolean savedHideGui = false;

    private static long enteredAtMs = 0L;

    private static boolean pendingCapture = false;
    private static boolean cleanFrameReady = false;

    /** 最近一次拍照完成的时刻，用于白闪 */
    private static long flashAtMs = 0L;

    /** 自拍：保持第三人称背后视角，玩家自由调整后再拍 */
    public static boolean selfie = false;
    private static CameraType savedCameraType = null;

    private CameraMode() {}

    public static boolean isActive() { return active; }

    public static void enter() {
        if (active) return;
        Minecraft mc = Minecraft.getInstance();

        active = true;
        savedHideGui = mc.options.hideGui;
        mc.options.hideGui = true;   // 等效原版 F1，藏掉准星与物品栏
        enteredAtMs = System.currentTimeMillis();
        pendingCapture = false;
        cleanFrameReady = false;
        savedCameraType = null;
        if (selfie) {
            savedCameraType = mc.options.getCameraType();
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        }
    }

    public static void exit() {
        if (!active) return;

        // 还原而不是无脑置 false：玩家可能本来就自己按了 F1
        Minecraft mc = Minecraft.getInstance();
        mc.options.hideGui = savedHideGui;
        active = false;
        pendingCapture = false;
        cleanFrameReady = false;
        if (savedCameraType != null) {
            mc.options.setCameraType(savedCameraType);
            savedCameraType = null;
        }
    }

    public static void requestCapture() {
        if (!active || pendingCapture) return;
        pendingCapture = true;
        cleanFrameReady = false;
    }

    /** 取景框此刻是否应被抑制（拍照期间不能入镜） */
    public static boolean suppressOverlay() { return pendingCapture; }

    /** 由渲染层调用，告知本帧未绘制取景框 */
    public static void markCleanFrame() {
        if (pendingCapture) cleanFrameReady = true;
    }

    public static boolean shouldGrabNow() { return pendingCapture && cleanFrameReady; }

    /** 抓取完成后调用，恢复取景框并触发白闪 */
    public static void finishCapture() {
        pendingCapture = false;
        cleanFrameReady = false;
        flashAtMs = System.currentTimeMillis();
    }

    /** 切换自拍：开＝切第三人称背后并保持；关＝还原第一人称 */
    public static void toggleSelfie() {
        Minecraft mc = Minecraft.getInstance();
        selfie = !selfie;
        if (selfie) {
            savedCameraType = mc.options.getCameraType();
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        } else if (savedCameraType != null) {
            mc.options.setCameraType(savedCameraType);
            savedCameraType = null;
        }
    }

    public static long getEnteredAtMs() { return enteredAtMs; }
    public static long getFlashAtMs() { return flashAtMs; }
}
