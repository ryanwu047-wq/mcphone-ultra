package com.mcphoneultra.client.ui;

import com.november.mcphone.api.client.ui.PhoneCanvas;

/** 列表滚动状态：滚轮、拖拽条、内容高度裁剪。 */
public final class Scroller {

    private float offset;
    private int dragGrab = -1;      // 按住滚动条时的鼠标偏移
    private boolean dragging;

    public void reset() {
        offset = 0;
        dragging = false;
        dragGrab = -1;
    }

    public float offset() {
        return offset;
    }

    public float clamp(int contentH, int viewH) {
        float max = Math.max(0, contentH - viewH);
        if (offset > max) offset = max;
        if (offset < 0) offset = 0;
        return offset;
    }

    public boolean onWheel(double amount, int contentH, int viewH) {
        offset -= (float) amount * 14f;
        clamp(contentH, viewH);
        return true;
    }

    public void scrollBy(int px, int contentH, int viewH) {
        offset += px;
        clamp(contentH, viewH);
    }

    /** 在滚动条区域按下时调用：记录抓取位置 */
    public void grab(int mx, int my, int x, int y, int viewH, int contentH) {
        int track = viewH - 2;
        int thumb = Math.max(8, track * viewH / contentH);
        float maxOff = Math.max(1, contentH - viewH);
        int ty = y + 1 + (int) ((track - thumb) * offset / maxOff);
        dragging = Ui.hit(x, y + 1, 2, track, mx, my) && mx >= x && mx < x + 2;
        dragGrab = dragging ? my - ty : -1;
    }

    public void drag(int my, int x, int y, int viewH, int contentH) {
        if (!dragging) return;
        int track = viewH - 2;
        int thumb = Math.max(8, track * viewH / contentH);
        float maxOff = Math.max(1, contentH - viewH);
        int pos = my - y - 1 - dragGrab;
        offset = maxOff * pos / (track - thumb);
        clamp(contentH, viewH);
    }

    public void release() {
        dragging = false;
        dragGrab = -1;
    }

    public void draw(PhoneCanvas c, int x, int y, int viewH, int contentH) {
        Ui.scrollbar(c, x, y, viewH, contentH, offset());
    }
}
