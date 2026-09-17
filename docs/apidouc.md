# apidouc —— 商店定價、新增 App、複製 App 的 API 教學（本体篇）

本次更新直接在 **mcphone 本体源码**（`ryanwu047-wq/mcphone-ultra`，`mod_id=mcphone`、v1.9.1）里落地，
不是附属。配合 `docs/addon-api.md` 一起看。三件事：

1. **給商店的 App 分配不同價格**（鑽石）—— `com/mcphoneultra/server/store/UltraAppPrices.java`
2. **新增「複製」App** —— `com/mcphoneultra/client/app/CopyApp.java`
3. **複製 App 的實作模式**：64 鑽石／副手物品／NBT ≤ 5KB／可存鑽石

範例即實作：下面每一段都指向本倉庫 `src/main/java/` 下的真實代碼，直接可編譯、可運行。

---
## 1. 給商店的 App 分配不同價格

商店價格走 SPI：實作 `IAppPriceProvider`，回傳「App id → 價錢」的 Map，
MCphone 的 `AppPriceRegistry` 第一次查價時自動掃描所有 SPI 提供者並合併。

```java
// src/main/java/com/mcphoneultra/server/store/UltraAppPrices.java
package com.mcphoneultra.server.store;

import com.november.mcphone.api.cost.IAppPriceProvider;
import com.november.mcphone.api.cost.ICost;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.Map;

public final class UltraAppPrices implements IAppPriceProvider {

    private static ResourceLocation ultra(String path) {
        return ResourceLocation.fromNamespaceAndPath("mcphone_ultra", path);
    }

    @Override
    public Map<ResourceLocation, ICost> prices() {
        Map<ResourceLocation, ICost> out = new LinkedHashMap<>();

        // 複製 App：每次複製消耗 64 鑽石
        out.put(ultra("copy"), ICost.of(Items.DIAMOND, 64));

        // 商店其他可下載 App：不同 App 不同價格，混用不同物品
        out.put(ultra("phone"), ICost.of(Items.EMERALD, 24));       // 語音群組通話 —— 24 綠寶石
        out.put(ultra("mailbox"), ICost.of(Items.EMERALD, 16));     // 好友寄信與物品 —— 16 綠寶石
        out.put(ultra("starshooter"), ICost.of(Items.GOLD_INGOT, 8));   // STG 小遊戲 —— 8 金錠
        out.put(ultra("memory"), ICost.of(Items.ENDER_PEARL, 8));   // 記憶配對 —— 8 終界珍珠
        out.put(ultra("tetris"), ICost.of(Items.GOLD_INGOT, 4));    // 下落方塊 —— 4 金錠
        out.put(ultra("alarm"), ICost.of(Items.IRON_INGOT, 8));     // 真實時間提醒 —— 8 鐵錠
        out.put(ultra("flashlight"), ICost.of(Items.IRON_INGOT, 4));    // 手電筒 —— 4 鐵錠
        out.put(ultra("splash"), ICost.of(Items.EMERALD, 2));       // 開機問候卡 —— 2 綠寶石
        out.put(ultra("lucky"), ICost.of(Items.GOLD_INGOT, 2));     // 搖一搖骰子 —— 2 金錠

        return out;
    }
}
```

SPI 註冊（文件放 `src/main/resources/META-INF/services/`）：

```
文件: META-INF/services/com.november.mcphone.api.cost.IAppPriceProvider
內容: com.november.mcphone.feature.store.BuiltinAppPrices
      com.mcphoneultra.server.store.UltraAppPrices
```

要點：

- `ICost.of(ItemLike, int)` 就是「N 個某物品」的價錢；`ICost.emc(long)` 是 EMC，`ICost.FREE` 是免費。
  每個 App 可以收**不同物品**（鑽石、綠寶石、金錠、鐵錠、終界珍珠…），任意原版/模組物品都能當價錢。
- **id 必須與 `IPhoneApp.getId()` 完全一致**（含命名空間），否則對不上價。
- **`isPreinstalled() == false` 的 App 才會進商店販售**；預裝 App 在商店顯示「已安裝」，價格不生效。
- 購買由 MCphone 內建處理（客戶端發包、服務端核對 `canAfford` 並真扣），你只需要報價。
- **放在非 client 包**（這裡是 `server/store`）：報價在服務端也要讀（真扣款），
  引用 `net.minecraft.client.*` 會被 `verifyDistIsolation` 擋下。

---
## 2. 新增一個 App

實作 `IPhoneApp`（或繼承自己的 `BaseApp`），SPI 註冊即可。

```java
// src/main/java/com/mcphoneultra/client/app/CopyApp.java（節選）
public final class CopyApp extends BaseApp {

    public CopyApp() {
        super("copy", false);   // false = 不預裝，進商店等玩家花鑽石買
    }

    @Override
    protected IPhonePage createPage() {
        return new CopyPage();
    }
}
```

SPI 註冊（**追加**到既有文件，一行一個類）：

```
文件: META-INF/services/com.november.mcphone.api.client.app.IPhoneApp
內容: ...（原有 53 行不動）
      com.mcphoneultra.client.app.CopyApp
```

要點：

- 語言鍵：`assets/mcphone/lang/en_us.json`、`zh_cn.json` 各加
  `mcphone_ultra.app.copy` 與 `mcphone_ultra.app.copy.desc`（格式：4 空格縮進、冒號後兩空格）。
- 圖標：`assets/mcphone_ultra/textures/app/copy.png`，20×20 PNG-32。

---
## 3. 複製 App 的實作模式

「複製」App 做的事：讀取玩家**副手物品**，把它的完整數據（含 NBT）序列化存進手機，
每次消耗 **64 鑽石**，序列化後 **NBT 不可大於 5KB**。手機裡可**存鑽石**（餘額），複製從餘額扣。

### 3.1 序列化與 5KB 上限

1.21.1 的物品數據在 `ItemStack.save(RegistryAccess)` 裡（回傳 `Tag`，實際是 `CompoundTag`）：

```java
RegistryAccess regs = mc.level.registryAccess();
CompoundTag tag = (CompoundTag) off.save(regs);   // 副手物品的完整 NBT
byte[] data = compress(tag);                       // 壓縮成字節
if (data.length > 5 * 1024) {                      // 5KB 上限，超限拒絕
    // 告訴玩家「NBT 過大」，一分錢都不扣
    return;
}
NbtIo.writeCompressed(tag, SAVES.resolve(file));   // 存成 .nbt 檔
```

```java
private static byte[] compress(CompoundTag tag) {
    try {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, out);   // net.minecraft.nbt.NbtIo
        return out.toByteArray();
    } catch (IOException e) {
        throw new UncheckedIOException("NBT 壓縮失敗", e);
    }
}
```

- **為什麼先壓縮再比大小**：`NbtIo.writeCompressed` 是實際落盤的格式，用它算出來的字節數才是玩家真實佔用。
- 讀回來用 `NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap())`，還原物品用 `ItemStack.parse(registryAccess, tag)`。

### 3.2 鑽石錢包（存 / 領 / 扣）

餘額存在 `Store`（JSON 小助手，每 App 一檔，原子寫入）：

```java
private final Store store = Store.of("copy");              // config/mcphone/ultra/state/copy.json
private int balance()  { return store.getInt("diamonds", 0); }
private void setBalance(int v) { store.setInt("diamonds", Math.max(0, v)); }
```

操作背包要拿「真」玩家。**純客戶端 mod 的關鍵點**：多人連線時客戶端背包只是鏡像，
扣了會跨檔回滾；單人遊戲要拿整合伺服器的 `ServerPlayer` 才算數：

```java
private static Player inventoryPlayer() {
    Minecraft mc = Minecraft.getInstance();
    if (mc.getSingleplayerServer() != null && mc.player != null) {
        ServerPlayer sp = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
        if (sp != null) return sp;      // 單人：對整合伺服器操作，真實扣/給
    }
    return mc.player;                   // 多人：退化成僅本機鏡像
}
```

存/領/扣都直接掃背包逐格操作（別依賴 `Inventory.add` 以外的捷徑，逐格最穩）：

```java
private static void removeDiamonds(Player p, int count) {
    int need = count;
    for (int i = 0; i < p.getInventory().getContainerSize() && need > 0; i++) {
        ItemStack s = p.getInventory().getItem(i);
        if (s.isEmpty() || !s.is(Items.DIAMOND)) continue;
        int take = Math.min(need, s.getCount());
        s.shrink(take);
        need -= take;
    }
}
```

### 3.3 複製動作的完整順序

1. 副手為空 → 拒絕。
2. 餘額 < 64 → 拒絕。
3. 序列化 NBT，`> 5120 B` → 拒絕（**先別扣錢**）。
4. 存檔 `config/mcphone/ultra/copy/saves/<物品名>_<時間戳>.nbt`（`Paths.safeName` 防目錄穿越）。
5. 餘額 − 64，重新載入列表。

先檢查後扣錢，失敗的操作一分錢都不該動——這是付費功能的基本原則。

---
## 4. 對照表：你該用哪個 API

| 想做的事 | 用哪個 API | 怎麼註冊 |
|---|---|---|
| 給 App 定價 | `cost.IAppPriceProvider` + `cost.ICost` | SPI：`...cost.IAppPriceProvider` |
| 新增一個 App | `client.app.IPhoneApp` | SPI：`...client.app.IPhoneApp` |
| 畫一頁手機介面 | `client.ui.IPhonePage` / `PhoneCanvas` | `openPage()` 回傳 |
| 商店多一個來源 | `client.store.IAppSource` | SPI：`...client.store.IAppSource` |
| 副手物品 | 原版 `Player#getOffhandItem()` | 不用註冊 |
| NBT 存檔 | 原版 `NbtIo`（1.21.1） | 不用註冊 |
| 單人真實扣背包 | `Minecraft#getSingleplayerServer()` → `ServerPlayer` | 不用註冊 |

---
## 5. 構建與驗證

本體是 NeoForge 21.1.248 / MC 1.21.1 / JDK 21。構建前必須指到 JDK 21：

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.12.1"
.\gradlew.bat build --console=plain -x test
```

構建內建兩道校驗：

- `verifyDistIsolation`：非 client 包的類不得引用 `net.minecraft.client.*`（UltraAppPrices 放 `server/store` 就是為此）。
- `verifyServiceFiles`：SPI 服務文件裡的每個類都必須真實存在。
