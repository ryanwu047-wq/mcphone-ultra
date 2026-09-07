package com.mcphoneultra.server;

import com.mcphoneultra.client.net.CloudPackets;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.tags.ItemTags;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 隨身熔爐（手機內 UI）：每 tick 在服務端燒煉，VIP 加速（VIP 2×、SVIP 4×）。
 * 燃料按 tick 消耗，1 煤仍燒 8 個物品——加速只縮短時間，不改變燃料產出比。
 */
public final class FurnaceServer {

    public static final int COOK_TIME = 200;

    public static final class State {
        public ItemStack input = ItemStack.EMPTY;
        public ItemStack fuel = ItemStack.EMPTY;
        public ItemStack output = ItemStack.EMPTY;
        public int progress;
        public int burnTicks;
        public int speed = 1;
    }

    private static final Map<UUID, State> states = new HashMap<>();
    private static int tickCounter;

    private FurnaceServer() {
    }

    public static void open(ServerPlayer p) {
        // 真實熔爐行為：重開介面時保留上一次的內容與進度，繼續燒
        State st = states.get(p.getUUID());
        if (st == null) {
            st = new State();
            states.put(p.getUUID(), st);
        }
        st.speed = CloudDriveData.get(p.serverLevel()).tierOf(p).furnaceSpeed();
        send(p);
    }

    public static void putInput(ServerPlayer p) {
        State st = states.get(p.getUUID());
        if (st == null) return;
        ItemStack hand = p.getOffhandItem();
        if (hand.isEmpty()) return;
        if (st.input.isEmpty()) {
            st.input = hand.copy();
            hand.setCount(0);
        } else if (ItemStack.isSameItemSameComponents(st.input, hand) && st.input.getCount() < 64) {
            int add = Math.min(hand.getCount(), 64 - st.input.getCount());
            st.input.grow(add);
            hand.shrink(add);
        }
        p.inventoryMenu.broadcastChanges();
        send(p);
    }

    public static void putFuel(ServerPlayer p) {
        State st = states.get(p.getUUID());
        if (st == null) return;
        ItemStack hand = p.getOffhandItem();
        if (hand.isEmpty()) return;
        if (burnTime(hand) <= 0) return;
        if (st.fuel.isEmpty()) {
            st.fuel = hand.copy();
            hand.setCount(0);
        } else if (ItemStack.isSameItemSameComponents(st.fuel, hand) && st.fuel.getCount() < 64) {
            int add = Math.min(hand.getCount(), 64 - st.fuel.getCount());
            st.fuel.grow(add);
            hand.shrink(add);
        }
        p.inventoryMenu.broadcastChanges();
        send(p);
    }

    public static void takeOutput(ServerPlayer p) {
        State st = states.get(p.getUUID());
        if (st == null || st.output.isEmpty()) return;
        ItemStack out = st.output;
        int take = Math.min(out.getCount(), 64);
        ItemStack give = out.copy();
        give.setCount(take);
        int placed = placeInInventory(p, give);
        if (placed <= 0) return;
        out.shrink(placed);
        if (out.isEmpty()) st.output = ItemStack.EMPTY;
        p.inventoryMenu.broadcastChanges();
        send(p);
    }

    public static void close(ServerPlayer p) {
        // 真實熔爐：關閉介面不還物品、不複製——東西留在熔爐裡，重開還在
        State st = states.get(p.getUUID());
        if (st == null) return;
        p.inventoryMenu.broadcastChanges();
    }

    /** 玩家登出/斷線時才把殘留物還給玩家：否則伺服器重啟會憑空消失 */
    public static void closeAndGiveBack(ServerPlayer p) {
        State st = states.remove(p.getUUID());
        if (st == null) return;
        giveBack(p, st.input);
        giveBack(p, st.fuel);
        giveBack(p, st.output);
        p.inventoryMenu.broadcastChanges();
    }

    private static void giveBack(ServerPlayer p, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        int left = placeInInventory(p, stack);
        if (left > 0) {
            p.drop(new ItemStack(stack.getItem(), left), false);
        }
    }

    /** 玩家登出時強制歸還物品：斷線/關服不走 close 路徑，狀態留在 map 會憑空消失 */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) closeAndGiveBack(sp);
    }

    /** 每 tick 推進所有在線熔爐，每 5 tick 同步一次 */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (states.isEmpty()) return;
        tickCounter++;
        ServerLevel level = event.getServer().overworld();
        Iterator<Map.Entry<UUID, State>> it = states.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, State> e = it.next();
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
            if (p == null) {
                it.remove();
                continue;
            }
            State st = e.getValue();
            tick(st, level);
            if (tickCounter % 5 == 0) send(p);
        }
    }

    private static void tick(State st, ServerLevel level) {
        if (st.burnTicks > 0) st.burnTicks--;
        if (st.burnTicks <= 0 && !st.fuel.isEmpty()
                && canSmelt(st, level) && burnTime(st.fuel) > 0) {
            st.burnTicks = burnTime(st.fuel);
            st.fuel.shrink(1);
            if (st.fuel.isEmpty()) st.fuel = ItemStack.EMPTY;
        }
        if (st.burnTicks > 0 && canSmelt(st, level)) {
            st.progress += st.speed;
            if (st.progress >= COOK_TIME) {
                st.progress = 0;
                finishSmelt(st, level);
            }
        } else if (st.burnTicks <= 0) {
            st.progress = 0;
        }
    }

    private static boolean canSmelt(State st, ServerLevel level) {
        if (st.input.isEmpty()) return false;
        var recipe = level.getRecipeManager().getRecipeFor(
                RecipeType.SMELTING, new SingleRecipeInput(st.input), level);
        if (recipe.isEmpty()) return false;
        ItemStack result = recipe.get().value().getResultItem(level.registryAccess());
        if (result.isEmpty()) return false;
        if (st.output.isEmpty()) return true;
        return ItemStack.isSameItemSameComponents(st.output, result) && st.output.getCount() < 64;
    }

    private static void finishSmelt(State st, ServerLevel level) {
        var recipe = level.getRecipeManager().getRecipeFor(
                RecipeType.SMELTING, new SingleRecipeInput(st.input), level);
        if (recipe.isEmpty()) return;
        ItemStack result = recipe.get().value().getResultItem(level.registryAccess());
        if (st.output.isEmpty()) {
            st.output = result.copy();
        } else {
            st.output.grow(result.getCount());
        }
        st.input.shrink(1);
        if (st.input.isEmpty()) st.input = ItemStack.EMPTY;
    }

    private static void send(ServerPlayer p) {
        State st = states.get(p.getUUID());
        if (st == null) return;
        PacketDistributor.sendToPlayer(p, new CloudPackets.FurnaceStateS2C(
                st.input, st.fuel, st.output, st.progress, st.burnTicks, st.speed));
    }

    /** 簡化燃料表（tick 數）：覆蓋常見燃料即可 */
    private static int burnTime(ItemStack s) {
        if (s.is(Items.COAL) || s.is(Items.CHARCOAL)) return 1600;
        if (s.is(Items.COAL_BLOCK)) return 16000;
        if (s.is(Items.LAVA_BUCKET)) return 20000;
        if (s.is(Items.BLAZE_ROD)) return 2400;
        if (s.is(Items.DRIED_KELP_BLOCK)) return 4000;
        if (s.is(Items.DRIED_KELP)) return 200;
        if (s.is(Items.BAMBOO)) return 50;
        if (s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS) || s.is(ItemTags.WOODEN_STAIRS)
                || s.is(ItemTags.WOODEN_FENCES) || s.is(ItemTags.WOODEN_TRAPDOORS)
                || s.is(ItemTags.WOODEN_DOORS) || s.is(ItemTags.WOODEN_SLABS)
                || s.is(ItemTags.WOODEN_PRESSURE_PLATES)) return 300;
        if (s.is(Items.STICK) || s.is(ItemTags.WOODEN_BUTTONS)
                ) return 100;
        return 0;
    }

    private static int placeInInventory(ServerPlayer p, ItemStack give) {
        var inv = p.getInventory();
        int n = give.getCount();
        for (int i = 0; i < 36 && n > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, give) && s.getCount() < 64) {
                int add = Math.min(n, 64 - s.getCount());
                s.grow(add);
                n -= add;
            }
        }
        for (int i = 0; i < 36 && n > 0; i++) {
            if (inv.getItem(i).isEmpty()) {
                int add = Math.min(n, 64);
                ItemStack copy = give.copy();
                copy.setCount(add);
                inv.setItem(i, copy);
                n -= add;
            }
        }
        return give.getCount() - n;
    }
}
