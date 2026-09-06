package com.mcphoneultra.client.util;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/** 图像读取与贴图上传助手：任意格式（经 ImageIO）→ NativeImage → DynamicTexture。 */
public final class Images {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private Images() {}

    /** 任意图片（PNG/JPG/GIF 首帧…）→ NativeImage。失败返回 null。 */
    public static NativeImage readAny(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            BufferedImage src = ImageIO.read(in);
            if (src == null) return null;
            return toNative(src);
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
