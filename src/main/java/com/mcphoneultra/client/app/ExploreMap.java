package com.mcphoneultra.client.app;

import com.mcphoneultra.client.io.Paths;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 🗺 探索地圖資料：記錄玩家走過的 chunk 的地表色與高度，存
 * files/map/&lt;維度&gt;.txt（每行 x,z,color,height）。支援在已探索區域
 * 用 A* 找「最平的路」（高度差當 penalty）。
 */
public final class ExploreMap {

    private ExploreMap() {
    }

    public static final class Cell {
        public int color;
        public int height;
    }

    private static final Map<String, Map<Long, Cell>> WORLDS = new HashMap<>();
    private static int tickCounter;

    /** 每次全局 tick 調用（關著手機也在探索），40 tick ≈ 2 秒記一格 */
    public static void onClientTick() {
        if (++tickCounter % 40 != 0) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        recordAt(mc.player.getX(), mc.player.getY(), mc.player.getZ());
    }

    public static void recordAt(double px, double py, double pz) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        String dim = mc.level.dimension().location().getPath();
        int cx = (int) Math.floor(px / 16), cz = (int) Math.floor(pz / 16);
        long key = key(cx, cz);
        Map<Long, Cell> map = WORLDS.computeIfAbsent(dim, k -> new HashMap<>());
        if (map.containsKey(key)) return;

        // 從玩家高度往下找最高非空氣方塊（最多 40 格）
        int x = (int) Math.floor(px), z = (int) Math.floor(pz);
        int y = (int) Math.floor(py);
        int color = 0xFF606060, height = y;
        for (int dy = 0; dy < 40; dy++) {
            int yy = y - dy;
            if (yy < mc.level.dimensionType().minY()) break;
            Block b = mc.level.getBlockState(new BlockPos(x, yy, z)).getBlock();
            if (b != Blocks.AIR && b != Blocks.CAVE_AIR && b != Blocks.VOID_AIR) {
                color = blockColor(b);
                height = yy;
                break;
            }
        }
        Cell cell = new Cell();
        cell.color = color;
        cell.height = height;
        map.put(key, cell);
        dirty = true;
    }

    private static boolean dirty;

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int blockColor(Block b) {
        if (b == Blocks.GRASS_BLOCK) return 0xFF58A030;
        if (b == Blocks.DIRT || b == Blocks.COARSE_DIRT) return 0xFF8B6B4A;
        if (b == Blocks.SAND || b == Blocks.RED_SAND) return 0xFFE8DCA0;
        if (b == Blocks.STONE) return 0xFF8A8A8A;
        if (b == Blocks.DEEPSLATE) return 0xFF5A5A5A;
        if (b == Blocks.WATER) return 0xFF3A6ECF;
        if (b == Blocks.LAVA) return 0xFFE05A1F;
        if (b == Blocks.SNOW_BLOCK || b == Blocks.POWDER_SNOW) return 0xFFF0F0F0;
        if (b == Blocks.GRAVEL) return 0xFF9A8A7A;
        if (b == Blocks.OAK_LOG || b == Blocks.BIRCH_LOG || b == Blocks.SPRUCE_LOG
                || b == Blocks.JUNGLE_LOG || b == Blocks.DARK_OAK_LOG || b == Blocks.ACACIA_LOG
                || b == Blocks.MANGROVE_LOG || b == Blocks.CHERRY_LOG) return 0xFF6B4F2E;
        if (b == Blocks.OAK_LEAVES || b == Blocks.BIRCH_LEAVES || b == Blocks.SPRUCE_LEAVES
                || b == Blocks.JUNGLE_LEAVES || b == Blocks.DARK_OAK_LEAVES || b == Blocks.ACACIA_LEAVES
                || b == Blocks.MANGROVE_LEAVES || b == Blocks.CHERRY_LEAVES) return 0xFF2F7F2F;
        if (b == Blocks.OAK_PLANKS || b == Blocks.BIRCH_PLANKS || b == Blocks.SPRUCE_PLANKS) return 0xFFA8825C;
        if (b == Blocks.NETHERRACK) return 0xFF7A2A2A;
        if (b == Blocks.SOUL_SAND || b == Blocks.SOUL_SOIL) return 0xFF6A5A3A;
        if (b == Blocks.END_STONE) return 0xFFD9CE8A;
        if (b == Blocks.OBSIDIAN) return 0xFF1A1030;
        if (b == Blocks.MYCELIUM) return 0xFF7A6A8A;
        if (b == Blocks.TERRACOTTA) return 0xFFA06040;
        if (b == Blocks.ICE || b == Blocks.PACKED_ICE || b == Blocks.BLUE_ICE) return 0xFFA8D8E8;
        if (b == Blocks.COBBLESTONE || b == Blocks.MOSSY_COBBLESTONE) return 0xFF7A7A7A;
        if (b == Blocks.BEDROCK) return 0xFF3A3A3A;
        if (b == Blocks.WARPED_NYLIUM || b == Blocks.WARPED_PLANKS) return 0xFF2F6A5A;
        if (b == Blocks.CRIMSON_NYLIUM || b == Blocks.CRIMSON_PLANKS) return 0xFF7A2A4A;
        return 0xFF606060;
    }

    /** 玩家所在的維度名 */
    public static String currentDim() {
        var mc = Minecraft.getInstance();
        return mc.level == null ? "overworld" : mc.level.dimension().location().getPath();
    }

    public static Map<Long, Cell> cells(String dim) {
        return WORLDS.computeIfAbsent(dim, k -> new HashMap<>());
    }

    // ---- 存檔 ----

    private static Path file(String dim) {
        return Paths.file("map", dim + ".txt");
    }

    public static void save(String dim) {
        if (!dirty) return;
        Map<Long, Cell> map = WORLDS.get(dim);
        if (map == null) return;
        try {
            Path p = file(dim);
            Files.createDirectories(p.getParent());
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<Long, Cell> e : map.entrySet()) {
                long k = e.getKey();
                int x = (int) (k >> 32), z = (int) k;
                sb.append(x).append(',').append(z).append(',')
                        .append(e.getValue().color).append(',')
                        .append(e.getValue().height).append('\n');
            }
            Files.write(p, sb.toString().getBytes(StandardCharsets.UTF_8));
            dirty = false;
        } catch (IOException ignored) {
        }
    }

    public static void load(String dim) {
        Map<Long, Cell> map = WORLDS.computeIfAbsent(dim, k -> new HashMap<>());
        Path p = file(dim);
        if (!Files.isRegularFile(p)) return;
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String[] f = line.split(",");
                if (f.length < 4) continue;
                try {
                    Cell c = new Cell();
                    c.color = Integer.parseInt(f[2]);
                    c.height = Integer.parseInt(f[3]);
                    map.put(key(Integer.parseInt(f[0]), Integer.parseInt(f[1])), c);
                } catch (Exception ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    // ---- A* 尋路（在已探索 chunk 網格上找高度差最小的路） ----

    public static final class PathResult {
        public List<long[]> chunks = new ArrayList<>(); // {x, z}
        public int steps;
        public boolean found;
    }

    public static PathResult findPath(String dim, double startX, double startZ,
                                      double goalX, double goalZ) {
        Map<Long, Cell> map = cells(dim);
        int sx = (int) Math.floor(startX / 16), sz = (int) Math.floor(startZ / 16);
        int gx = (int) Math.floor(goalX / 16), gz = (int) Math.floor(goalZ / 16);
        PathResult out = new PathResult();
        if (sx == gx && sz == gz) {
            out.found = true;
            out.chunks.add(new long[]{sx, sz});
            return out;
        }

        long sk = key(sx, sz), gk = key(gx, gz);
        Map<Long, Integer> gScore = new HashMap<>();
        Map<Long, Long> came = new HashMap<>();
        PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> Long.compare(a[1], b[1]));
        gScore.put(sk, 0);
        open.add(new long[]{sk, heuristic(sx, sz, gx, gz)});

        int[] dx = {1, -1, 0, 0, 1, 1, -1, -1};
        int[] dz = {0, 0, 1, -1, 1, -1, 1, -1};
        int explored = 0;

        while (!open.isEmpty() && explored < 20000) {
            long[] cur = open.poll();
            long ck = cur[0];
            if (ck == gk) {
                out.found = true;
                while (ck != sk) {
                    int cx = (int) (ck >> 32), cz = (int) ck;
                    out.chunks.add(0, new long[]{cx, cz});
                    ck = came.getOrDefault(ck, sk);
                }
                out.chunks.add(0, new long[]{sx, sz});
                out.steps = out.chunks.size() - 1;
                return out;
            }
            explored++;
            int cx = (int) (ck >> 32), cz = (int) ck;
            Cell ccell = map.get(ck);
            int ch = ccell == null ? 64 : ccell.height;
            for (int i = 0; i < 8; i++) {
                int nx = cx + dx[i], nz = cz + dz[i];
                long nk = key(nx, nz);
                Cell ncell = map.get(nk);
                // 只走已探索的格子；斜角也要求相鄰直線格已探索（不穿山）
                if (ncell == null) continue;
                if (i >= 4 && (map.get(key(cx + dx[i], cz)) == null
                        || map.get(key(cx, cz + dz[i])) == null)) continue;
                int nh = ncell.height;
                int cost = 10 + Math.abs(nh - ch) * 4; // 高度差越大＝越不平
                int ng = gScore.getOrDefault(ck, 0) + cost;
                if (ng < gScore.getOrDefault(nk, Integer.MAX_VALUE)) {
                    gScore.put(nk, ng);
                    came.put(nk, ck);
                    open.add(new long[]{nk, ng + heuristic(nx, nz, gx, gz) * 10});
                }
            }
        }
        out.found = false;
        return out;
    }

    private static int heuristic(int x, int z, int gx, int gz) {
        return Math.abs(x - gx) + Math.abs(z - gz);
    }
}
