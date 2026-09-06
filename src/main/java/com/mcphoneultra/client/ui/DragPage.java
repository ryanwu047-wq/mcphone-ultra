package com.mcphoneultra.client.ui;

/**
 * 需要拖拽與鬆手事件的 addon 頁面實作這個介面。
 *
 * mcphone 的 IPhonePage 只提供 mouseClicked/mouseScrolled；
 * 我們直接改 PhoneScreen 把 mouseDragged/mouseReleased 轉發給
 * instanceof DragPage 的頁面，繪圖/形狀/移動工具才做得出來。
 */
public interface DragPage {

    /** 拖拽中。dx/dy 是本次事件相對上一幀的位移；返回 true 表示消費 */
    default boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return false;
    }

    /** 鬆手。返回 true 表示消費 */
    default boolean mouseReleased(double mx, double my, int button) {
        return false;
    }
}
