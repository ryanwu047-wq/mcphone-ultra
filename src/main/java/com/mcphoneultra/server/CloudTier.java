package com.mcphoneultra.server;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 網盤會員等級。大箱子 = 54 格；容量 = 54 × 箱數。
 * 會員時長固定 30 天＝600 分鐘（真實時鐘，非遊戲時間）。
 */
public enum CloudTier {

    NONE("無會員", 1, 64, 0),
    VIP1("VIP1", 2, 64, 1),
    VIP2("VIP2", 2, 128, 2),
    VIP3("VIP3", 4, 128, 3),
    VIP4("VIP4", 6, 256, 4),
    VIP5("VIP5", 8, 256, 5),
    VIP6("VIP6", 12, 512, 6),
    SVIP1("SVIP1", 16, 512, 7),
    SVIP2("SVIP2", 32, 512, 8),
    SVIP3("SVIP3", 48, 1024, 9),
    SVIP4("SVIP4", 64, 1024, 10),
    SVIP5("SVIP5", 128, 4096, 11);

    public static final long DURATION_MS = 600L * 60L * 1000L; // 30 天＝600 分鐘真實時間

    public final String label;
    public final int boxes;
    public final int stackLimit;
    public final int order;

    CloudTier(String label, int boxes, int stackLimit, int order) {
        this.label = label;
        this.boxes = boxes;
        this.stackLimit = stackLimit;
        this.order = order;
    }

    /** 總格子數（大箱子 54 格） */
    public int slots() {
        return boxes * 54;
    }

    public boolean isVip() {
        return this != NONE;
    }

    /** 熔爐加速倍率：VIP 2×、SVIP 4× */
    public int furnaceSpeed() {
        if (this == NONE) return 1;
        return this.name().startsWith("SVIP") ? 4 : 2;
    }

    /** 附魔台等效書架數：VIP1=5、VIP2=10、VIP3 以上＝滿 15 */
    public int enchantBooks() {
        return switch (this) {
            case NONE -> 0;
            case VIP1 -> 5;
            case VIP2 -> 10;
            default -> 15;
        };
    }

    /** 購買價格（30 天） */
    public ItemStack price() {
        return switch (this) {
            case NONE -> ItemStack.EMPTY;
            case VIP1 -> count(Items.DIAMOND, 32);
            case VIP2 -> count(Items.DIAMOND, 64);
            case VIP3 -> count(Items.DIAMOND, 128);
            case VIP4 -> count(Items.DIAMOND, 256);
            case VIP5 -> count(Items.DIAMOND_BLOCK, 64);
            case VIP6 -> count(Items.NETHERITE_INGOT, 1);
            case SVIP1 -> count(Items.NETHERITE_INGOT, 1);
            case SVIP2 -> count(Items.NETHERITE_INGOT, 4);
            case SVIP3 -> count(Items.NETHERITE_INGOT, 16);
            case SVIP4 -> count(Items.NETHERITE_INGOT, 64);
            case SVIP5 -> count(Items.NETHERITE_BLOCK, 4);
        };
    }

    /** 價格描述文字（如「32 鑽石」） */
    public String priceLabel() {
        ItemStack p = price();
        if (p.isEmpty()) return "免費";
        Item it = p.getItem();
        String name = (it == Items.DIAMOND) ? "鑽石"
                : (it == Items.DIAMOND_BLOCK) ? "鑽石塊"
                : (it == Items.NETHERITE_INGOT) ? "合金錠"
                : (it == Items.NETHERITE_BLOCK) ? "合金塊" : "?";
        return p.getCount() + " " + name;
    }

    private static ItemStack count(Item item, int n) {
        return new ItemStack(item, n);
    }
}
