package com.example.extraction.manager;

import com.example.extraction.ExtractionPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 闸门状态管理器。
 *
 * <p>维护 {@code switchId -> 是否已拉闸} 与 {@code switchId -> 冷却结束时间}。
 * 状态会落盘到 data.yml，避免服务器重启后闸门状态丢失。
 */
public class SwitchManager {

    private final ExtractionPlugin plugin;
    private final File dataFile;

    private final Map<String, Boolean> active = new HashMap<>();
    private final Map<String, Long> cooldownEnd = new HashMap<>();

    /** 记录拉闸者，便于审计。 */
    private final Map<String, UUID> lastOperator = new HashMap<>();

    public SwitchManager(ExtractionPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
    }

    // ------------------------------------------------------------------

    public void load() {
        active.clear();
        cooldownEnd.clear();
        lastOperator.clear();
        if (!dataFile.exists()) return;
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        if (cfg.isConfigurationSection("switches")) {
            for (String key : cfg.getConfigurationSection("switches").getKeys(false)) {
                active.put(key, cfg.getBoolean("switches." + key + ".active", false));
                long cd = cfg.getLong("switches." + key + ".cooldown-end", 0L);
                if (cd > System.currentTimeMillis()) cooldownEnd.put(key, cd);
            }
        }
        plugin.getLogger().info("已恢复 " + active.size() + " 条闸门状态。");
    }

    public void save() {
        FileConfiguration cfg = new YamlConfiguration();
        for (String key : active.keySet()) {
            cfg.set("switches." + key + ".active", active.get(key));
            Long cd = cooldownEnd.get(key);
            cfg.set("switches." + key + ".cooldown-end", cd == null ? 0L : cd);
            UUID op = lastOperator.get(key);
            cfg.set("switches." + key + ".last-operator", op == null ? null : op.toString());
        }
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("无法创建插件数据目录：" + plugin.getDataFolder());
            }
            cfg.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("保存闸门状态失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------

    public boolean isActive(String switchId) {
        return Boolean.TRUE.equals(active.get(switchId));
    }

    public void setActive(String switchId, boolean value) {
        active.put(switchId, value);
        save();
    }

    public void setCooldown(String switchId, int seconds) {
        if (seconds <= 0) return;
        cooldownEnd.put(switchId, System.currentTimeMillis() + seconds * 1000L);
    }

    /** 剩余冷却秒数，0 表示无冷却。 */
    public long getCooldownRemaining(String switchId) {
        Long end = cooldownEnd.get(switchId);
        if (end == null) return 0;
        long remain = end - System.currentTimeMillis();
        return remain <= 0 ? 0 : (long) Math.ceil(remain / 1000.0);
    }

    public void clearCooldown(String switchId) {
        cooldownEnd.remove(switchId);
    }

    public Map<String, Boolean> snapshot() {
        return new HashMap<>(active);
    }
}
