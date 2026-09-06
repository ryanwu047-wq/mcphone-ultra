package com.mcphoneultra.client.util;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;

/** 图像读取与贴图上传助手：任意格式（经 ImageIO）→ NativeImage → DynamicTexture。 */
public final class Images {

    /** 檢視用圖上限：4096×4096（4K 截圖都涵蓋）。防假 PNG 宣告 99999×99999 在解碼時 OOM */
    private static final int MAX_SIDE = 4096;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private Images() {}

    /**
     * 任意图片（PNG/JPG/GIF 首帧…）→ NativeImage。失败或超过尺寸上限返回 null。
     * 解碼前先讀 header 驗尺寸，避免「解壓炸彈」在 ImageIO.read 階段吃爆記憶體。
     */
    public static NativeImage readAny(Path p) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(Files.newInputStream(p))) {
            if (iis == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE) {
                    return null;
                }
                BufferedImage src = reader.read(0);
                if (src == null) return null;
                return toNative(src);
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static NativeImage toNative(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        NativeImage out = new NativeImage(w, h, true);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                // ARGB → ABGR
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                out.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        return out;
    }

    /** 把 NativeImage 注册成贴图，返回可渲染的句柄。必须在渲染线程调用。 */
    public static Tex upload(NativeImage img, String name) {
        ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(
                "mcphone_ultra", "dyn/" + name + "_" + SEQ.incrementAndGet());
        Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(img));
        return new Tex(loc, img.getWidth(), img.getHeight());
    }

    public static void release(Tex tex) {
        if (tex == null) return;
        try {
            Minecraft.getInstance().getTextureManager().release(tex.loc());
        } catch (Exception ignored) {
        }
    }

    public record Tex(ResourceLocation loc, int w, int h) {}
}
