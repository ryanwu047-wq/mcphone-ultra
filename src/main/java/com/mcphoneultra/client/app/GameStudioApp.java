package com.mcphoneultra.client.app;

import com.google.gson.JsonObject;
import com.mcphoneultra.client.io.Paths;
import com.mcphoneultra.client.io.Store;
import com.mcphoneultra.client.ui.Scroller;
import com.mcphoneultra.client.ui.Ui;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 圖形化遊戲製造工具：格子上放 牆/豆子/敵人/出口/玩家，就能玩自己的小遊戲。
 * 地圖存成 JSON 在 files/games/，可以分享給別人載入。
 */
public final class GameStudioApp extends BaseApp {

    public GameStudioApp() {
        super("gamemaker", true);
    }

    @Override
    protected IPhonePage createPage() {
        return new GameStudioPage();
    }

    private static final class GameStudioPage extends ClickablePage {
        private static final int COLS = 12, ROWS = 8;
        // 0=空 1=牆 2=豆子 3=玩家 4=出口 5=敵人
        private final int[][] grid = new int[ROWS][COLS];
        private int tool = 2;
        private boolean playMode;
        private int playerR, playerC, enemyDir = 1;
        private int beans;
        private boolean won, lost;
        private long lastEnemyMove;
        private String gameName = "我的遊戲";
        private boolean naming;
        private final Scroller fileScroller = new Scroller();
        private boolean showFiles;
        private String toast = "";
        private long toastUntil;

        private void toast(String s) {
            toast = s;
            toastUntil = System.currentTimeMillis() + 2000;
        }

        private void clearGrid() {
            for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) grid[r][c] = 0;
        }

        private void newGame() {
            clearGrid();
            grid[ROWS / 2][1] = 3;      // 玩家在左
            grid[ROWS / 2][COLS - 2] = 4; // 出口在右
            grid[ROWS / 2][5] = 2;
            grid[ROWS / 2][6] = 2;
            grid[ROWS / 2][7] = 2;
            grid[3][3] = 1;
            grid[3][8] = 1;
            grid[4][5] = 1;
            playMode = false;
        }

        private void save() {
            JsonObject o = new JsonObject();
            o.addProperty("name", gameName);
            StringBuilder sb = new StringBuilder();
            for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) sb.append(grid[r][c]);
            o.addProperty("map", sb.toString());
            try {
                Files.createDirectories(Paths.dir("games"));
                Path p = Paths.file("games", gameName + ".json");
                Files.writeString(p, o.toString(), java.nio.charset.StandardCharsets.UTF_8);
                toast("已儲存 " + gameName);
            } catch (IOException e) {
                toast("儲存失敗");
            }
        }

        private void load(String name) {
            try {
                Path p = Paths.file("games", name + ".json");
                JsonObject o = com.google.gson.JsonParser.parseString(Files.readString(p)).getAsJsonObject();
                gameName = o.has("name") ? o.get("name").getAsString() : name;
                String map = o.get("map").getAsString();
                clearGrid();
                int idx = 0;
                for (int r = 0; r < ROWS; r++) {
                    for (int c = 0; c < COLS; c++) {
                        if (idx < map.length()) {
                            grid[r][c] = map.charAt(idx) - '0';
                        }
                        idx++;
                    }
                }
                playMode = false;
                toast("已載入 " + gameName);
            } catch (Exception e) {
                toast("載入失敗");
            }
        }

        private List<String> listGames() {
            List<String> out = new ArrayList<>();
            Path dir = Paths.dir("games");
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                        .map(p -> p.getFileName().toString().replace(".json", ""))
                        .forEach(out::add);
            } catch (IOException ignored) {
            }
            out.sort(Comparator.naturalOrder());
            return out;
        }

        private void startPlay() {
            beans = 0;
            won = lost = false;
            enemyDir = 1;
            for (int r = 0; r < ROWS; r++) {
                for (int c = 0; c < COLS; c++) {
                    if (grid[r][c] == 3) {
                        playerR = r;
                        playerC = c;
                    }
                    if (grid[r][c] == 2) beans++;
                }
            }
            playMode = true;
            lastEnemyMove = 0;
        }

        private void movePlayer(int dr, int dc) {
            int nr = playerR + dr, nc = playerC + dc;
            if (nr < 0 || nr >= ROWS || nc < 0 || nc >= COLS) return;
            int cell = grid[nr][nc];
            if (cell == 1) return;
            grid[playerR][playerC] = 0;
            playerR = nr;
            playerC = nc;
            if (cell == 2) beans--;
            if (cell == 4 && beans == 0) {
                grid[nr][nc] = 3;
                won = true;
                return;
            }
            grid[nr][nc] = 3;
            checkEnemyHit();
        }

        private void checkEnemyHit() {
            for (int r = 0; r < ROWS; r++) {
                for (int c = 0; c < COLS; c++) {
                    if (grid[r][c] == 5 && r == playerR && c == playerC) lost = true;
                }
            }
        }

        private void stepEnemy() {
            if (System.currentTimeMillis() - lastEnemyMove < 400) return;
            lastEnemyMove = System.currentTimeMillis();
            for (int r = 0; r < ROWS; r++) {
                for (int c = 0; c < COLS; c++) {
                    if (grid[r][c] == 5) {
                        int nc = c + enemyDir;
                        if (nc < 0 || nc >= COLS || grid[r][nc] == 1 || grid[r][nc] == 4) {
                            enemyDir = -enemyDir;
                            nc = c + enemyDir;
                            if (nc < 0 || nc >= COLS || grid[r][nc] == 1 || grid[r][nc] == 4) continue;
                        }
                        if (grid[r][nc] == 3) {
                            lost = true;
                            return;
                        }
                        grid[r][c] = 0;
                        grid[r][nc] = 5;
                        checkEnemyHit();
                        return;
                    }
                }
            }
        }

        @Override
        public void onOpen() {
            newGame();
        }

        @Override
        public void render(PhoneCanvas canvas) {
            int x = canvas.x(), y = canvas.y(), w = canvas.width(), h = canvas.height();
            var s = canvas.style();
            GuiGraphics g = canvas.graphics();
            Ui.fill(g, x, y, w, h, s.screenBackground());

            if (showFiles) {
                renderFiles(canvas, x, y, w, h);
                return;
            }

            String title = (playMode ? "▶ " : "✎ ") + gameName;
            Ui.textClipped(canvas, title, x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            int by = y + 13;
            int bh = 12;
            if (clickOn(x + 1, by, 34, bh)) {
                playMode = !playMode;
                if (playMode) startPlay();
            }
            Ui.button(canvas, x + 1, by, 34, bh, true, canvas.hovered(x + 1, by, 34, bh));
            Ui.buttonLabel(canvas, x + 1, by, 34, bh, playMode ? "✎ 編輯" : "▶ 遊玩", true);

            if (clickOn(x + 37, by, 30, bh)) {
                naming = true;
            }
            Ui.button(canvas, x + 37, by, 30, bh, true, canvas.hovered(x + 37, by, 30, bh));
            Ui.buttonLabel(canvas, x + 37, by, 30, bh, "命名", true);

            if (clickOn(x + 69, by, 26, bh)) save();
            Ui.button(canvas, x + 69, by, 26, bh, true, canvas.hovered(x + 69, by, 26, bh));
            Ui.buttonLabel(canvas, x + 69, by, 26, bh, "存檔", true);

            if (clickOn(x + 97, by, 26, bh)) showFiles = true;
            Ui.button(canvas, x + 97, by, 26, bh, true, canvas.hovered(x + 97, by, 26, bh));
            Ui.buttonLabel(canvas, x + 97, by, 26, bh, "讀檔", true);

            if (clickOn(x + 125, by, 26, bh)) newGame();
            Ui.button(canvas, x + 125, by, 26, bh, true, canvas.hovered(x + 125, by, 26, bh));
            Ui.buttonLabel(canvas, x + 125, by, 26, bh, "新建", true);

            if (playMode) {
                if (!won && !lost) stepEnemy();
                String status = won ? "🎉 過關！" : lost ? "💀 被敵人抓到了" : "還差 " + beans + " 顆豆子";
                Ui.text(canvas, status, x + 3, y + 28, won ? 0xFFFFD54F : lost ? 0xFFFF6B6B : s.subtleColor());
            } else {
                Ui.text(canvas, toolLabel(tool), x + 3, y + 28, s.subtleColor());
            }

            int gy = y + 42;
            int area = Math.min(w - 8, h - 42 - 20);
            int cell = area / COLS;
            int gx = x + (w - cell * COLS) / 2;

            for (int r = 0; r < ROWS; r++) {
                for (int c = 0; c < COLS; c++) {
                    int cx = gx + c * cell, cy = gy + r * cell;
                    if (!playMode && clickOn(cx, cy, cell, cell)) {
                        grid[r][c] = tool;
                    }
                    if (!playMode && rightClickOn(cx, cy, cell, cell)) {
                        grid[r][c] = 0;
                    }
                    Ui.fill(g, cx, cy, cell, cell, cellColor(grid[r][c]));
                    Ui.border(canvas, cx, cy, cell, cell, 0xFF1A2028);
                }
            }

            if (!playMode) {
                // 工具列
                int ty = gy + cell * ROWS + 4;
                int tw2 = Math.max(16, (w - 12) / 6);
                String[] tools = {"擦除", "牆", "豆子", "玩家", "出口", "敵人"};
                for (int i = 0; i < 6; i++) {
                    int tx = x + 2 + i * tw2;
                    boolean sel = tool == (i == 0 ? 0 : i);
                    if (clickOn(tx, ty, tw2 - 2, bh)) tool = i == 0 ? 0 : i;
                    Ui.button(canvas, tx, ty, tw2 - 2, bh, true, canvas.hovered(tx, ty, tw2 - 2, bh));
                    Ui.buttonLabel(canvas, tx, ty, tw2 - 2, bh, (sel ? "● " : "") + tools[i], true);
                }
            }

            if (naming) {
                int px = x + 6, py = y + 60;
                Ui.fill(g, px, py, w - 12, 56, 0xFF202830);
                Ui.border(canvas, px, py, w - 12, 56, s.accentColor());
                Ui.text(canvas, "遊戲名稱（Enter 確定）", px + 3, py + 3, s.titleColor());
                Ui.fill(g, px + 3, py + 16, w - 18, 12, 0xFF000000);
                Ui.textClipped(canvas, gameName + (System.currentTimeMillis() / 500 % 2 == 0 ? "▏" : ""),
                        px + 5, py + 17, s.bodyColor(), px, py, w - 12, 56);
                if (clickOn(px + 3, py + 32, 50, 12)) {
                    naming = false;
                    if (gameName.trim().isEmpty()) gameName = "我的遊戲";
                }
                Ui.button(canvas, px + 3, py + 32, 50, 12, true, canvas.hovered(px + 3, py + 32, 50, 12));
                Ui.buttonLabel(canvas, px + 3, py + 32, 50, 12, "確定", true);
                if (clickOn(px + 57, py + 32, 50, 12)) naming = false;
                Ui.button(canvas, px + 57, py + 32, 50, 12, true, canvas.hovered(px + 57, py + 32, 50, 12));
                Ui.buttonLabel(canvas, px + 57, py + 32, 50, 12, "取消", true);
            }

            if (System.currentTimeMillis() < toastUntil && !toast.isEmpty()) {
                Ui.drawCentered(canvas, toast, x, y + h - 16, w, 12, s.titleColor());
            }
        }

        private void renderFiles(PhoneCanvas canvas, int x, int y, int w, int h) {
            var s = canvas.style();
            GuiGraphics g = canvas.graphics();
            Ui.fill(g, x, y, w, h, s.screenBackground());
            Ui.text(canvas, "📂 我的遊戲", x + 3, y + 2, s.titleColor());
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());
            List<String> games = listGames();
            int listY = y + 14, listH = h - 28, rowH = 12;
            fileScroller.clamp(games.size() * rowH, listH);
            int off = (int) fileScroller.offset();
            for (int i = 0; i < games.size(); i++) {
                int ry = listY + i * rowH - off;
                if (ry + rowH < listY || ry > listY + listH) continue;
                if (clickOn(x, ry, w - 2, rowH)) {
                    load(games.get(i));
                    showFiles = false;
                }
                if (canvas.hovered(x, ry, w - 2, rowH)) Ui.fill(g, x, ry, w - 2, rowH, s.pressedOverlay());
                Ui.textClipped(canvas, "▸ " + games.get(i), x + 3, ry + 1, s.bodyColor(), x, ry, w, rowH);
            }
            Ui.scrollbar(canvas, x + w - 3, listY, listH, games.size() * rowH, off);
            int by = y + h - 14;
            if (clickOn(x + 1, by, w - 2, 12)) showFiles = false;
            Ui.button(canvas, x + 1, by, w - 2, 12, true, canvas.hovered(x + 1, by, w - 2, 12));
            Ui.buttonLabel(canvas, x + 1, by, w - 2, 12, "← 返回", true);
        }

        private static int cellColor(int v) {
            return switch (v) {
                case 1 -> 0xFF8D6E63;   // 牆
                case 2 -> 0xFFFFD54F;   // 豆子
                case 3 -> 0xFF4FC3F7;   // 玩家
                case 4 -> 0xFF81C784;   // 出口
                case 5 -> 0xFFE57373;   // 敵人
                default -> 0xFF2C3542;
            };
        }

        private static String toolLabel(int t) {
            return switch (t) {
                case 1 -> "工具：牆";
                case 2 -> "工具：豆子";
                case 3 -> "工具：玩家（起點）";
                case 4 -> "工具：出口";
                case 5 -> "工具：敵人";
                default -> "工具：擦除（右鍵同效）";
            };
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (naming) {
                if (key == GLFW.GLFW_KEY_ENTER) {
                    naming = false;
                    if (gameName.trim().isEmpty()) gameName = "我的遊戲";
                } else if (key == GLFW.GLFW_KEY_BACKSPACE && !gameName.isEmpty()) {
                    gameName = gameName.substring(0, gameName.length() - 1);
                }
                return true;
            }
            if (playMode && !won && !lost) {
                switch (key) {
                    case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> movePlayer(-1, 0);
                    case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> movePlayer(1, 0);
                    case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> movePlayer(0, -1);
                    case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> movePlayer(0, 1);
                    default -> {
                        return false;
                    }
                }
                return true;
            }
            return false;
        }

        @Override
        public boolean charTyped(char ch, int mods) {
            if (naming) {
                if (ch >= 32 && ch != 127) gameName += ch;
                return true;
            }
            return false;
        }

        @Override
        public boolean capturesKeyboard() {
            return naming || playMode;
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            if (showFiles) {
                fileScroller.onWheel(amount, listGames().size() * 12, 130);
            }
            return true;
        }

        @Override
        public boolean onBack() {
            if (showFiles) {
                showFiles = false;
                return true;
            }
            if (naming) {
                naming = false;
                return true;
            }
            if (playMode) {
                playMode = false;
                return true;
            }
            return false;
        }
    }
}
