package com.mcphoneultra.server;

import com.mcphoneultra.MCphoneUltra;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.UUID;

/**
 * ☎ 電話＝Simple Voice Chat 語音群組：
 * 撥號＝建立一個「電話-&lt;玩家名&gt;」群組（建群者自動加入），對方在遊戲內
 * 按 U 加入同名群組即可語音通話；掛斷＝移除該群組。
 * 全程反射，沒裝 SVC 時 serverApi 為 null，只提示不崩潰。
 */
public final class VoiceCall {

    private VoiceCall() {
    }

    public static boolean svcAvailable() {
        return UltraVoicePlugin.serverApi != null;
    }

    public static String groupName(ServerPlayer player) {
        return "電話-" + player.getGameProfile().getName();
    }

    /** 撥號：建立群組並通知目標玩家 */
    public static void call(ServerPlayer caller, String targetName) {
        Object api = UltraVoicePlugin.serverApi;
        if (api == null) {
            caller.sendSystemMessage(Component.literal("☎ 未安裝 Simple Voice Chat（語音 mod），電話無法使用"));
            return;
        }
        String name = groupName(caller);
        if (findGroup(api, name) != null) {
            caller.sendSystemMessage(Component.literal("☎ 你已在通話中（群組「" + name + "」已存在）"));
            return;
        }
        try {
            Method create = api.getClass().getMethod("createGroup", String.class, String.class);
            create.invoke(api, name, "");
            caller.sendSystemMessage(Component.literal(
                    "☎ 已撥號！對方按 U → 群組 → 加入「" + name + "」即可通話（你也已自動加入）"));
            if (targetName != null && !targetName.isBlank()) {
                ServerPlayer target = caller.server.getPlayerList().getPlayerByName(targetName.trim());
                if (target != null) {
                    target.sendSystemMessage(Component.literal(
                            "☎ " + caller.getGameProfile().getName() + " 打給你！按 U → 群組 → 加入「"
                                    + name + "」通話"));
                }
            }
        } catch (Exception e) {
            MCphoneUltra.LOGGER.warn("語音群組建立失敗", e);
            caller.sendSystemMessage(Component.literal("☎ 語音群組建立失敗：" + e.getMessage()));
        }
    }

    /** 掛斷：移除自己的群組 */
    public static void hangup(ServerPlayer player) {
        Object api = UltraVoicePlugin.serverApi;
        if (api == null) {
            player.sendSystemMessage(Component.literal("☎ 未安裝 Simple Voice Chat"));
            return;
        }
        Object g = findGroup(api, groupName(player));
        if (g == null) {
            player.sendSystemMessage(Component.literal("☎ 你沒有進行中的通話"));
            return;
        }
        try {
            Method getId = g.getClass().getMethod("getId");
            UUID id = (UUID) getId.invoke(g);
            Method remove = api.getClass().getMethod("removeGroup", UUID.class);
            remove.invoke(api, id);
            player.sendSystemMessage(Component.literal("☎ 已掛斷"));
        } catch (Exception e) {
            MCphoneUltra.LOGGER.warn("語音群組移除失敗", e);
            player.sendSystemMessage(Component.literal("☎ 掛斷失敗：" + e.getMessage()));
        }
    }

    private static Object findGroup(Object api, String name) {
        try {
            Method getGroups = api.getClass().getMethod("getGroups");
            Collection<?> groups = (Collection<?>) getGroups.invoke(api);
            if (groups == null) return null;
            Method getName = null;
            for (Object g : groups) {
                if (getName == null) getName = g.getClass().getMethod("getName");
                if (name.equals(getName.invoke(g))) return g;
            }
        } catch (Exception e) {
            MCphoneUltra.LOGGER.warn("查詢語音群組失敗", e);
        }
        return null;
    }
}
