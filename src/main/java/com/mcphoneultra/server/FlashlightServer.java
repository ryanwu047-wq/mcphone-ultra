package com.mcphoneultra.server;

import com.mcphoneultra.MCphoneUltra;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 手電筒：開啟時在玩家頭頂放一個 15 級隱形光源方塊，跟著玩家移動。
 * 關閉/登出時把原本的方塊還原回去。全程只動玩家頭頂一格，不污染世界。
 */
public final class FlashlightServer {

    private static final class State {
        BlockPos pos;
        BlockState covered;
    }

    private static final Map<UUID, State> states = new HashMap<>();

    private FlashlightServer() {
    }

    public static boolean isOn(ServerPlayer p) {
        return states.containsKey(p.getUUID());
    }

    public static void toggle(ServerPlayer p) {
        State st = states.get(p.getUUID());
        if (st != null) {
            restore(p.serverLevel(), st);
            states.remove(p.getUUID());
            MCphoneUltra.LOGGER.info("手電筒關閉: {}", p.getGameProfile().getName());
        } else {
            st = new State();
            place(p.serverLevel(), p, st);
            states.put(p.getUUID(), st);
            MCphoneUltra.LOGGER.info("手電筒開啟: {}", p.getGameProfile().getName());
        }
    }

    private static void place(ServerLevel level, ServerPlayer p, State st) {
        BlockPos pos = p.blockPosition().above();
        if (level.getBlockState(pos).is(Blocks.LIGHT)) return; // 已有光源，不動
        st.pos = pos;
        st.covered = level.getBlockState(pos);
        level.setBlockAndUpdate(pos, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15));
    }

    private static void restore(ServerLevel level, State st) {
        if (st.pos == null || st.covered == null) return;
        // 只有當那個位置還是我們放的光源才還原，避免覆蓋玩家後來放/挖的方塊
        if (level.getBlockState(st.pos).is(Blocks.LIGHT)) {
            level.setBlockAndUpdate(st.pos, st.covered);
        }
        st.pos = null;
        st.covered = null;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (states.isEmpty()) return;
        ServerLevel level = event.getServer().overworld();
        Iterator<Map.Entry<UUID, State>> it = states.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, State> e = it.next();
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
            if (p == null) {
                it.remove(); // 離線：殘留光源下次世界存檔時自然消失，不強求還原
                continue;
            }
            State st = e.getValue();
            BlockPos now = p.blockPosition().above();
            if (st.pos == null || !st.pos.equals(now)) {
                restore(level, st);
                place(level, p, st);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            State st = states.remove(sp.getUUID());
            if (st != null) restore(sp.serverLevel(), st);
        }
    }
}
