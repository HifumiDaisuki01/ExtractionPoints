package com.example.extraction.model;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 物品条件：撤离需要持有的物品。
 */
public class ItemCondition {

    private final Material material;
    private final int amount;
    private final boolean consume;
    /** 可选的显示名包含关键字，用于区分同名物品（例如「保险箱钥匙」）。 */
    private final String nameContains;
    /** 是否使用 PDC 精确匹配（进阶，留作扩展）。 */
    private final String pdcKey;

    public ItemCondition(Material material, int amount, boolean consume, String nameContains, String pdcKey) {
        this.material = material;
        this.amount = Math.max(1, amount);
        this.consume = consume;
        this.nameContains = nameContains;
        this.pdcKey = pdcKey;
    }

    public static ItemCondition fromConfig(ConfigurationSection sec) {
        if (sec == null) return null;
        String matName = sec.getString("material", "PAPER");
        Material mat = Material.matchMaterial(matName);
        if (mat == null) return null;
        int amount = sec.getInt("amount", 1);
        boolean consume = sec.getBoolean("consume", true);
        String nameContains = sec.getString("name-contains", null);
        String pdcKey = sec.getString("pdc-key", null);
        return new ItemCondition(mat, amount, consume, nameContains, pdcKey);
    }

    public Material getMaterial() {
        return material;
    }

    public int getAmount() {
        return amount;
    }

    public boolean isConsume() {
        return consume;
    }

    public String getNameContains() {
        return nameContains;
    }

    public String getPdcKey() {
        return pdcKey;
    }

    @Override
    public String toString() {
        return material.name() + " x" + amount + (consume ? " (消耗)" : " (保留)");
    }
}
