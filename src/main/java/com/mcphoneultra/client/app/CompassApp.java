package com.mcphoneultra.client.app;

import com.mcphoneultra.client.ui.Ui;
import com.november.mcphone.api.client.ui.IPhonePage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 🧭 指南針：顯示座標、朝向、與「家」路標的方位與距離。
 * 純客戶端，無需網路。
 */
public final class CompassApp extends BaseApp {

    public CompassApp() {
        super("compass", true);
    }

    @Override
    protected IPhonePage createPage() {
        return new CompassPage();
    }

    private static final class CompassPage extends ClickablePage {
        private double homeX = Double.NaN;
        private double homeZ = Double.NaN;
        private String toast = "";
        private long toastUntil;

        private void toast(String s) {
            toast = s;
            toastUntil = System.currentTimeMillis() + 2000;
        }

        @Override
        public void render(com.november.mcphone.api.client.ui.PhoneCanvas c) {
            int x = c.x(), y = c.y(), w = c.width(), h = c.height();
            var s = c.style();
            GuiGraphics g = c.graphics();
            Ui.fill(g, x, y, w, h, s.screenBackground());

            Ui.textClipped(c, "🧭 指南針", x + 3, y + 2, s.titleColor(), x, y, w, 12);
            if (clickOn(x + w - 40, y + 1, 37, 10) && Minecraft.getInstance().player != null) {
                var p = Minecraft.getInstance().player;
                homeX = p.getX();
                homeZ = p.getZ();
                toast("已設定家");
            }
            Ui.button(c, x + w - 40, y + 1, 37, 10, true, c.hovered(x + w - 40, y + 1, 37, 10));
            Ui.buttonLabel(c, x + w - 40, y + 1, 37, 10, "設家", true);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            var p = Minecraft.getInstance().player;
            if (p == null) {
                Ui.drawCentered(c, "不在世界裡", x, y + h / 2, w, 12, s.subtleColor());
                return;
            }

            // 羅盤盤面（r=30 保證 120 寬手機內不溢出）
            int cx = x + w / 2;
            int cy = y + 38;
            int r = 30;
            Ui.circle(g, cx, cy, r, 0xFF1A1F26);
            Ui.circle(g, cx, cy, r, s.accentColor());
            float yaw = p.getYRot();
            double rad = Math.toRadians(yaw);
            int nx = (int) (cx + Math.sin(rad) * (r - 7));
            int nz = (int) (cy - Math.cos(rad) * (r - 7));
            Ui.hline(g, cx - 2, cx + 2, cy - r + 6, 0xFFAA0000);
            Ui.fill(g, cx - 2, cy - r + 3, 4, 5, 0xFFFF3B30);
            drawLine(g, cx, cy, nx, nz, s.titleColor());
            Ui.drawCentered(c, "N", cx - 4, cy - r + 4, 8, 10, 0xFFFF3B30);

            // 資訊（全部截斷，120 寬不溢出）
            int iy = cy + r + 12;
            Ui.textClipped(c, "座標 X " + (int) p.getX() + "  Y " + (int) p.getY() + "  Z " + (int) p.getZ(),
                    x + 8, iy, s.titleColor(), x, y, w, 12);
            Ui.textClipped(c, "朝向  " + facing(yaw), x + 8, iy + 11, s.bodyColor(), x, y, w, 12);
            if (!Double.isNaN(homeX)) {
                double dx = homeX - p.getX();
                double dz = homeZ - p.getZ();
                double dist = Math.sqrt(dx * dx + dz * dz);
                double angle = Math.toDegrees(Math.atan2(dx, dz));
                Ui.textClipped(c, "家 距離 " + (int) dist + "m  " + cardinal(angle),
                        x + 8, iy + 22, s.accentColor(), x, y, w, 12);
            } else {
                Ui.textClipped(c, "家 未設定（按「設家」）", x + 8, iy + 22, s.subtleColor(), x, y, w, 12);
            }

            if (System.currentTimeMillis() < toastUntil && !toast.isEmpty()) {
                Ui.drawCentered(c, toast, x, y + h - 14, w, 12, s.titleColor());
            }
        }

        private static void drawLine(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
            int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
            int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
            int err = dx - dy;
            while (true) {
                Ui.fill(g, x0, y0, 1, 1, color);
                if (x0 == x1 && y0 == y1) break;
                int e2 = 2 * err;
                if (e2 > -dy) {
                    err -= dy;
                    x0 += sx;
                }
                if (e2 < dx) {
                    err += dx;
                    y0 += sy;
                }
            }
        }

        private static String facing(float yaw) {
            int d = (int) Math.floor(((yaw + 22.5f) % 360 + 360) % 360 / 45);
            String[] names = {"南(S)", "西南(SW)", "西(W)", "西北(NW)", "北(N)", "東北(NE)", "東(E)", "東南(SE)"};
            return names[d];
        }

        private static String cardinal(double angle) {
            int d = (int) Math.floor(((angle + 22.5) % 360 + 360) % 360 / 45);
            String[] names = {"南(S)", "西南(SW)", "西(W)", "西北(NW)", "北(N)", "東北(NE)", "東(E)", "東南(SE)"};
            return names[d];
        }
    }
}
