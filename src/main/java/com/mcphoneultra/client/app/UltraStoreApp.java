package com.mcphoneultra.client.app;

import com.mcphoneultra.client.io.Paths;
import com.mcphoneultra.client.ui.Scroller;
import com.mcphoneultra.client.ui.Ui;
import com.mcphoneultra.client.util.Http;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import com.november.mcphone.api.client.ui.PhoneStyle;
import net.minecraft.client.gui.GuiGraphics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * App 商店（mcphone-ultra）：瀏覽內建全家桶、檢查 mcphone 官方更新、
 * 下載「商店限定」小 App（啟用後出現在桌面）。
 */
public final class UltraStoreApp extends BaseApp {

    public UltraStoreApp() {
        super("ultrastore", true);
    }

    @Override
    protected IPhonePage createPage() {
        return new StorePage();
    }

    private static final class StorePage extends ClickablePage {
        private static final String[][] BUILTIN = {
                { "檔案管理", "網頁下載·hex/文字/代碼編輯·zip/7z/rar 壓縮解壓" },
                { "影片工作室", "影片剪輯與觀看" },
                { "JS 編程", "JavaScript 直譯執行" },
                { "Python 編程", "Python 直譯執行" },
                { "Termux", "命令列終端" },
                { "Bilibili", "B 站影片" },
                { "YouTube", "YouTube 影片" },
                { "網頁瀏覽器", "文字瀏覽 + 系統開啟" },
                { "相機", "螢幕拍照 + 膠捲" },
                { "VR 連動", "雙目預覽與玩家視角" },
                { "繪圖", "縮放·文字·圖形·RGB" },
                { "螢幕錄製", "錄幀 + ffmpeg 合成 mp4" },
                { "遊戲製作工具", "圖形化關卡編輯" },
                { "掃雷", "9×9 經典" },
                { "2048", "滑動合成" },
                { "貪吃蛇", "經典貪吃蛇" },
                { "俄羅斯方塊", "下落方塊" },
                { "太空射擊", "STG 小遊戲" },
                { "翻牌記憶", "記憶配對" },
                { "模擬器", "NES/GB/GBA" },
                { "忘憂鈴", "搖一搖的小確幸" },
                { "晨光卡片", "開機問候卡" },
        };
        private static final String[][] EXCLUSIVE = {
                { "系統狀態", "即時顯示 FPS/記憶體/位置（商店限定）", "sysinfo" },
                { "萬能骰子", "d20 冒險骰（商店限定）", "dice" },
        };

        private final Scroller scroller = new Scroller();
        private String toast = "";
        private long toastUntil;
        private String latest = "";
        private boolean checking;
        private String curVersion = "";

        private void toast(String s) {
            toast = s;
            toastUntil = System.currentTimeMillis() + 2500;
        }

        private static boolean exclusiveInstalled(String id) {
            return Files.isRegularFile(Paths.file("files", "store", id + ".installed"));
        }

        private void checkUpdate() {
            checking = true;
            Thread t = new Thread(() -> {
                String info = Http.get("https://api.github.com/repos/november521/mcphone/releases/latest").orElse("");
                String tag = "";
                int i = info.indexOf("\"tag_name\"");
                if (i >= 0) {
                    int a = info.indexOf('"', i + 12);
                    int b = info.indexOf('"', a + 1);
                    if (a >= 0 && b > a) tag = info.substring(a + 1, b);
                }
                final String v = tag;
                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    latest = v;
                    checking = false;
                    if (v.isEmpty()) toast("查不到（可能沒網路）");
                    else toast("最新版 " + v);
                });
            }, "mcphone-ultra-store-update");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void onOpen() {
            curVersion = net.neoforged.fml.ModList.get().getModContainerById("mcphone")
                    .map(m -> m.getModInfo().getVersion().toString()).orElse("?");
        }

        @Override
        public void render(PhoneCanvas c) {
            int x = c.x(), y = c.y(), w = c.width(), h = c.height();
            var s = c.style();
            GuiGraphics g = c.graphics();
            Ui.fill(g, x, y, w, h, s.screenBackground());

            Ui.textClipped(c, "🛍 App 商店", x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            int by = y + 13;
            if (clickOn(x + 1, by, 60, 12)) checkUpdate();
            Ui.button(c, x + 1, by, 60, 12, !checking, c.hovered(x + 1, by, 60, 12));
            Ui.buttonLabel(c, x + 1, by, 60, 12, checking ? "檢查中…" : "檢查更新", !checking);
            Ui.textClipped(c, "本機 mcphone " + curVersion
                            + (latest.isEmpty() ? "" : "  ·  最新 " + latest),
                    x + 64, by + 1, s.subtleColor(), x, by, w, 12);
            if (!latest.isEmpty() && !latest.equals("v" + curVersion)
                    && clickOn(x + 64, by, w - 66, 12)) {
                Http.openExternal("https://github.com/november521/mcphone/releases/latest");
                toast("已用系統瀏覽器開啟下載頁");
            }

            int listY = y + 30, rowH = 13;
            int visible = (h - 30 - 16) / rowH;
            int rows = BUILTIN.length + EXCLUSIVE.length + 1;
            scroller.clamp(rows * rowH, h - 30 - 16);
            int off = (int) scroller.offset();

            int idx = 0;
            drawRow(c, s, g, listY, off, idx++, "內建全家桶（已預裝）", "共 " + BUILTIN.length + " 個 App，全部直接內建", true);
            for (String[] app : BUILTIN) {
                drawRow(c, s, g, listY, off, idx++, "· " + app[0], app[1], false);
            }
            drawRow(c, s, g, listY, off, idx++, "商店限定", "可在此「下載」啟用", true);
            for (String[] app : EXCLUSIVE) {
                boolean inst = exclusiveInstalled(app[2]);
                int ry = listY + idx * rowH - off;
                if (ry + rowH >= listY && ry <= listY + (h - 30 - 16)) {
                    if (clickOn(x + w - 30, ry, 28, rowH) && !inst) {
                        try {
                            Files.writeString(Paths.file("files", "store", app[2] + ".installed"),
                                    "1", StandardCharsets.UTF_8);
                            toast("已下載並啟用「" + app[0] + "」（重開手機出現在桌面）");
                        } catch (IOException e) {
                            toast("下載失敗");
                        }
                    }
                    if (inst) {
                        Ui.buttonLabel(c, x + w - 30, ry, 28, rowH, "已安裝", false);
                    } else {
                        Ui.button(c, x + w - 30, ry, 28, rowH, true, c.hovered(x + w - 30, ry, 28, rowH));
                        Ui.buttonLabel(c, x + w - 30, ry, 28, rowH, "下載", true);
                    }
                    Ui.textClipped(c, "· " + app[0], x + 3, ry, s.bodyColor(), x, ry, w - 34, rowH);
                    Ui.textClipped(c, app[1], x + 44, ry, s.subtleColor(), x, ry, w - 76, rowH);
                }
                idx++;
            }
            Ui.scrollbar(c, x + w - 3, listY, h - 30 - 16, rows * rowH, off);

            if (System.currentTimeMillis() < toastUntil && !toast.isEmpty()) {
                Ui.drawCentered(c, toast, x, y + h - 14, w, 12, s.titleColor());
            }
        }

        private void drawRow(PhoneCanvas c, PhoneStyle s, GuiGraphics g, int listY, int off,
                             int idx, String title, String sub, boolean header) {
            int x = c.x(), w = c.width();
            int ry = listY + idx * 13 - off;
            if (ry + 13 < listY || ry > listY + c.height() - 46) return;
            if (header) {
                Ui.textClipped(c, "▌" + title, x + 3, ry, s.titleColor(), x, ry, w, 13);
                Ui.textClipped(c, sub, x + 30, ry, s.subtleColor(), x, ry, w, 13);
            } else {
                Ui.textClipped(c, title, x + 3, ry, s.bodyColor(), x, ry, w, 13);
            }
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            scroller.onWheel(amount, (BUILTIN.length + EXCLUSIVE.length + 2) * 13, 130);
            return true;
        }
    }
}
