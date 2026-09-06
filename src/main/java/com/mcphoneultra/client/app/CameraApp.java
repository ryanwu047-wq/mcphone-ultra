package com.mcphoneultra.client.app;

import com.mcphoneultra.client.io.Paths;
import com.mcphoneultra.client.ui.Scroller;
import com.mcphoneultra.client.ui.Ui;
import com.mcphoneultra.client.util.Images;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import com.november.mcphone.api.client.ui.PhoneStyle;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.Screenshot;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 相機：把遊戲畫面拍成照片存進 files/camera/，可瀏覽與檢視膠捲。 */
public final class CameraApp extends BaseApp {

    public CameraApp() {
        super("camera", true);
    }

    @Override
    protected IPhonePage createPage() {
        return new CameraPage();
    }

    private static final class CameraPage extends ClickablePage {
        private final List<String> photos = new ArrayList<>();
        private final Scroller scroller = new Scroller();
        private String viewing;
        private Images.Tex viewingTex;
        private String toast = "";
        private long toastUntil;

        private void toast(String s) {
            toast = s;
            toastUntil = System.currentTimeMillis() + 2000;
        }

        private void reload() {
            photos.clear();
            Path dir = Paths.dir("camera");
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png"))
                        .map(p -> p.getFileName().toString().replace(".png", ""))
                        .forEach(photos::add);
            } catch (IOException ignored) {
            }
            photos.sort(Comparator.reverseOrder());
            scroller.reset();
        }

        private void snap() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getMainRenderTarget() == null) {
                toast("沒有畫面可拍");
                return;
            }
            try {
                NativeImage img = Screenshot.takeScreenshot(mc.getMainRenderTarget());
                Path dir = Paths.dir("camera");
                String name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                Path p = dir.resolve(name + ".png");
                BufferedImage bi = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
                for (int yy = 0; yy < img.getHeight(); yy++) {
                    for (int xx = 0; xx < img.getWidth(); xx++) {
                        int abgr = img.getPixelRGBA(xx, yy);
                        int a = (abgr >>> 24) & 0xFF, b = (abgr >>> 16) & 0xFF;
                        int g = (abgr >>> 8) & 0xFF, r = abgr & 0xFF;
                        bi.setRGB(xx, yy, (a << 24) | (r << 16) | (g << 8) | b);
                    }
                }
                img.close();
                ImageIO.write(bi, "png", p.toFile());
                // 照片旁註：拍攝時的座標／維度／朝向
                try {
                    var player = mc.player;
                    if (player != null) {
                        String dim = player.level().dimension().location().getPath();
                        String info = "X " + (int) player.getX() + "  Y " + (int) player.getY()
                                + "  Z " + (int) player.getZ() + "  " + dim
                                + "  朝向 " + Math.round(player.getYRot()) + "°";
                        Files.write(dir.resolve(name + ".txt"),
                                info.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                } catch (IOException ignored) {
                }
                toast("已拍照 " + name);
                reload();
            } catch (Exception e) {
                toast("拍照失敗");
            }
        }

        private void view(String name) {
            viewing = name;
            if (viewingTex != null) Images.release(viewingTex);
            viewingTex = null;
            NativeImage img = Images.readAny(Paths.file("camera", name + ".png"));
            if (img != null) {
                viewingTex = Images.upload(img, "photo");
            } else {
                toast("讀取失敗");
            }
        }

        @Override
        public void onOpen() {
            reload();
        }

        @Override
        public void onClose() {
            if (viewingTex != null) {
                Images.release(viewingTex);
                viewingTex = null;
            }
        }

        @Override
        public void render(PhoneCanvas c) {
            int x = c.x(), y = c.y(), w = c.width(), h = c.height();
            var s = c.style();
            GuiGraphics g = c.graphics();
            Ui.fill(g, x, y, w, h, s.screenBackground());

            if (viewing != null) {
                renderView(c, x, y, w, h, s, g);
                return;
            }

            Ui.textClipped(c, "📷 相機", x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            int by = y + 13, bh = 12;
            if (clickOn(x + 1, by, 44, bh)) snap();
            Ui.button(c, x + 1, by, 44, bh, true, c.hovered(x + 1, by, 44, bh));
            Ui.buttonLabel(c, x + 1, by, 44, bh, "📸 拍照", true);
            if (clickOn(x + 47, by, 44, bh)) reload();
            Ui.button(c, x + 47, by, 44, bh, true, c.hovered(x + 47, by, 44, bh));
            Ui.buttonLabel(c, x + 47, by, 44, bh, "重新整理", true);

            Ui.text(c, "膠捲（" + photos.size() + " 張）", x + 3, y + 28, s.subtleColor());

            int listY = y + 42, listH = h - 42 - 16, rowH = 12;
            scroller.clamp(photos.size() * rowH, listH);
            int off = (int) scroller.offset();
            for (int i = 0; i < photos.size(); i++) {
                int ry = listY + i * rowH - off;
                if (ry + rowH < listY || ry > listY + listH) continue;
                if (clickOn(x, ry, w - 2, rowH)) view(photos.get(i));
                if (c.hovered(x, ry, w - 2, rowH)) Ui.fill(g, x, ry, w - 2, rowH, s.pressedOverlay());
                Ui.textClipped(c, "🖼 " + photos.get(i), x + 3, ry + 1, s.bodyColor(), x, ry, w, rowH);
                Ui.hline(g, x + 2, x + w - 2, ry + rowH - 1, s.buttonDisabledColor());
            }
            Ui.scrollbar(c, x + w - 3, listY, listH, photos.size() * rowH, off);

            if (System.currentTimeMillis() < toastUntil && !toast.isEmpty()) {
                Ui.drawCentered(c, toast, x, y + h - 14, w, 12, s.titleColor());
            }
        }

        private void renderView(PhoneCanvas c, int x, int y, int w, int h, PhoneStyle s, GuiGraphics g) {
            Ui.fill(g, x, y, w, h, s.screenBackground());
            Ui.textClipped(c, "🖼 " + viewing + ".png", x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            // 顯示拍照時的座標旁註（拍照時寫的 <名>.txt）
            try {
                java.nio.file.Path note = Paths.file("camera", viewing + ".txt");
                if (java.nio.file.Files.isRegularFile(note)) {
                    String info = java.nio.file.Files.readString(note);
                    Ui.textClipped(c, "📍 " + info, x + 3, y + 13, s.accentColor(), x, y, w, 12);
                }
            } catch (Exception ignored) {
            }

            if (clickOn(x + w - 50, y + 13, 48, 12)) {
                viewing = null;
                if (viewingTex != null) {
                    Images.release(viewingTex);
                    viewingTex = null;
                }
            }
            Ui.button(c, x + w - 50, y + 13, 48, 12, true, c.hovered(x + w - 50, y + 13, 48, 12));
            Ui.buttonLabel(c, x + w - 50, y + 13, 48, 12, "← 返回", true);

            if (viewingTex != null) {
                int areaW = w - 8, areaH = h - 32;
                float scale = Math.min(areaW / (float) viewingTex.w(), areaH / (float) viewingTex.h());
                int tw = (int) (viewingTex.w() * scale), th = (int) (viewingTex.h() * scale);
                int tx = x + (w - tw) / 2, ty = y + 16 + (h - 32 - th) / 2;
                g.blit(viewingTex.loc(), tx, ty, tw, th, 0, 0, viewingTex.w(), viewingTex.h(), viewingTex.w(), viewingTex.h());
                Ui.border(c, tx, ty, tw, th, s.buttonDisabledColor());
            }
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            if (viewing == null) {
                scroller.onWheel(amount, photos.size() * 12, 130);
            }
            return true;
        }

        @Override
        public boolean onBack() {
            if (viewing != null) {
                viewing = null;
                if (viewingTex != null) {
                    Images.release(viewingTex);
                    viewingTex = null;
                }
                return true;
            }
            return false;
        }
    }
}
