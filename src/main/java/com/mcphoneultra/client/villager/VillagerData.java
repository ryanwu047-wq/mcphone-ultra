package com.mcphoneultra.client.villager;

import com.mcphoneultra.client.io.Paths;
import net.minecraft.world.entity.npc.Villager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 綁定村民資料：蹲下＋右鍵村民後，把聯絡人存到 files/villagers/binds.txt。
 * 每行一個，tab 分隔：uuid 名稱 職業 維度 生態域 x y z 等級。
 */
public final class VillagerData {

    private VillagerData() {}

    public static Path file() {
        return Paths.file("villagers", "binds.txt");
    }

    public record Bound(UUID uuid, String name, String profession, String dimension,
                        String biome, int x, int y, int z, int level) {

        public String pos() {
            return x + ", " + y + ", " + z;
        }

        public String region() {
            String dim = dimension.contains(":") ? dimension.substring(dimension.lastIndexOf(':') + 1) : dimension;
            return dim + " / " + biome;
        }
    }

    /** 讀全部綁定（不存在回空清單） */
    public static List<Bound> load() {
        List<Bound> out = new ArrayList<>();
        Path p = file();
        if (!Files.isRegularFile(p)) return out;
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", -1);
                if (f.length < 9) continue;
                try {
                    out.add(new Bound(UUID.fromString(f[0]), f[1], f[2], f[3], f[4],
                            Integer.parseInt(f[5]), Integer.parseInt(f[6]), Integer.parseInt(f[7]),
                            Integer.parseInt(f[8])));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    /** 綁定一個村民（覆蓋同 uuid） */
    public static void bind(Villager v) {
        List<Bound> all = load();
        all.removeIf(b -> b.uuid().equals(v.getUUID()));
        all.add(0, new Bound(
                v.getUUID(),
                v.getName().getString(),
                v.getVillagerData().getProfession().name(),
                v.level().dimension().location().toString(),
                biomeName(v),
                v.blockPosition().getX(), v.blockPosition().getY(), v.blockPosition().getZ(),
                v.getVillagerData().getLevel()));
        save(all);
    }

    /** 解綁 */
    public static void unbind(UUID uuid) {
        List<Bound> all = load();
        all.removeIf(b -> b.uuid().equals(uuid));
        save(all);
    }

    /** 更新已綁定村民的即時資料（村民在附近時） */
    public static void update(Villager v) {
        List<Bound> all = load();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).uuid().equals(v.getUUID())) {
                all.set(i, new Bound(
                        v.getUUID(), v.getName().getString(),
                        v.getVillagerData().getProfession().name(),
                        v.level().dimension().location().toString(),
                        biomeName(v),
                        v.blockPosition().getX(), v.blockPosition().getY(), v.blockPosition().getZ(),
                        v.getVillagerData().getLevel()));
                break;
            }
        }
        save(all);
    }

    private static String biomeName(Villager v) {
        try {
            var holder = v.level().getBiome(v.blockPosition());
            var opt = holder.unwrapKey();
            if (opt.isEmpty()) return "?";
            String key = opt.get().location().toString();
            int idx = key.lastIndexOf(':');
            return idx >= 0 ? key.substring(idx + 1) : key;
        } catch (Exception e) {
            return "?";
        }
    }

    private static void save(List<Bound> all) {
        try {
            Files.createDirectories(file().getParent());
            StringBuilder sb = new StringBuilder();
            for (Bound b : all) {
                sb.append(b.uuid()).append('\t').append(b.name()).append('\t')
                        .append(b.profession()).append('\t').append(b.dimension()).append('\t')
                        .append(b.biome()).append('\t').append(b.x()).append('\t')
                        .append(b.y()).append('\t').append(b.z()).append('\t')
                        .append(b.level()).append('\n');
            }
            Files.write(file(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }
}
