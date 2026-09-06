package com.mcphoneultra.server;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;

/**
 * Simple Voice Chat 外掛入口：SVC 裝了才會載入本類（它掃描 @ForgeVoicechatPlugin
 * 註解）。API 實例存在靜態欄位，電話 App 只讀 Object、用反射呼叫——
 * SVC 沒裝時本類從不載入，mod 完全不受影響。
 */
@ForgeVoicechatPlugin
public final class UltraVoicePlugin implements VoicechatPlugin {

    /** 服務端 API（SVC 注入）。不 cast，給 VoiceCall 反射用 */
    public static volatile Object serverApi;

    @Override
    public String getPluginId() {
        return "mcphone_ultra";
    }

    @Override
    public void initialize(VoicechatApi api) {
        serverApi = api;
    }
}
