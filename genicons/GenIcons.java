import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

public class GenIcons {
    public static void main(String[] args) throws Exception {
        Map<String, Integer> apps = new LinkedHashMap<>();
        apps.put("filemanager", 0x4C9EFF);
        apps.put("videostudio", 0xFF6B9E);
        apps.put("codejs", 0xFFD866);
        apps.put("python", 0x3FA9F5);
        apps.put("termux", 0x40C057);
        apps.put("bilibili", 0xFF5FA0);
        apps.put("youtube", 0xFF4E45);
        apps.put("musiclab", 0xAB7BFF);
        apps.put("musicplayer", 0xFF9F5F);
        apps.put("speaker", 0xFF7B4E);
        apps.put("webbrowser", 0x4FC3F7);
        apps.put("camera", 0x8D6E63);
        apps.put("vr", 0x26A69A);
        apps.put("paint", 0xFF7043);
        apps.put("screenrec", 0xEF5350);
        apps.put("gamemaker", 0x66BB6A);
        apps.put("minesweeper", 0x9E9E9E);
        apps.put("game2048", 0xFFB74D);
        apps.put("snake", 0x43A047);
        apps.put("tetris", 0x5C6BC0);
        apps.put("starshooter", 0x7E57C2);
        apps.put("memory", 0xEC407A);
        apps.put("emulator", 0x29B6F6);
        apps.put("maimai", 0x66BB6A);
        apps.put("ultrastore", 0xFFA726);
        apps.put("lucky", 0xAB47BC);
        apps.put("splash", 0x42A5F5);

        File outDir = new File(args[0]);
        outDir.mkdirs();
        int idx = 0;
        for (Map.Entry<String, Integer> e : apps.entrySet()) {
            String id = e.getKey();
            int base = e.getValue();
            BufferedImage img = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
            // 背景
            for (int y = 0; y < 20; y++)
                for (int x = 0; x < 20; x++) {
                    boolean edge = x < 1 || y < 1 || x >= 19 || y >= 19;
                    img.setRGB(x, y, edge ? 0xFF000000 : shade(base, y < 10 ? 1.0 : 0.82));
                }
            // 中央白色圖形：依序號變換
            int cx = 10, cy = 10;
            for (int y = 5; y <= 14; y++)
                for (int x = 5; x <= 14; x++) {
                    boolean on = shape(idx, x - cx, y - cy);
                    if (on) img.setRGB(x, y, 0xFFFFFFFF);
                }
            // 角點
            img.setRGB(3, 3, 0xFFFFFFFF);
            img.setRGB(16, 3, 0xFFFFFFFF);
            img.setRGB(3, 16, 0xFFFFFFFF);
            img.setRGB(16, 16, 0xFFFFFFFF);
            ImageIO.write(img, "png", new File(outDir, id + ".png"));
            idx++;
        }
        System.out.println("generated " + apps.size() + " icons to " + outDir);
    }

    static int shade(int rgb, double f) {
        int r = (int) (((rgb >> 16) & 0xFF) * f);
        int g = (int) (((rgb >> 8) & 0xFF) * f);
        int b = (int) ((rgb & 0xFF) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    static boolean shape(int idx, int dx, int dy) {
        switch (idx % 8) {
            case 0: return Math.abs(dx) <= 3 && Math.abs(dy) <= 3;               // 方
            case 1: return Math.abs(dx) + Math.abs(dy) <= 4;                     // 菱
            case 2: return Math.abs(dx) <= 2 && Math.abs(dy) <= 4;               // 豎
            case 3: return Math.abs(dy) <= 2 && Math.abs(dx) <= 4;               // 橫
            case 4: return dx * dx + dy * dy <= 16;                              // 圓
            case 5: return dx == 0 && Math.abs(dy) <= 4 || dy == 0 && Math.abs(dx) <= 4; // 十字
            case 6: return dx * dx + dy * dy >= 8 && dx * dx + dy * dy <= 16;    // 環
            default: return Math.abs(dx * 2 - dy) <= 2 && Math.abs(dx + dy * 2) <= 4; // 斜
        }
    }
}
