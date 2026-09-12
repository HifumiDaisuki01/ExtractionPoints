package com.example.extraction.config;

import com.example.extraction.model.ExtractPoint;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 撤离点配置管理器。负责从 config.yml 加载并持有全部撤离点。
 */
public class ConfigManager {

    private final com.example.extraction.ExtractionPlugin plugin;
    private final Map<String, ExtractPoint> points = new LinkedHashMap<>();
    private int regionCheckInterval;

    public ConfigManager(com.example.extraction.ExtractionPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 重新加载全部配置。
     *
     * @return 成功加载的撤离点数量
     */
    public int load() {
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();
        cfg.options().copyDefaults(true);
        plugin.saveConfig();

        points.clear();
        regionCheckInterval = cfg.getInt("settings.region-check-interval", 5);

        ConfigurationSection root = cfg.getConfigurationSection("extraction-points");
        int loaded = 0;
        int failed = 0;
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection sec = root.getConfigurationSection(id);
                if (sec == null) continue;
                ExtractPoint point = ExtractPoint.fromConfig(id, sec);
                if (point == null) {
                    plugin.getLogger().warning("撤离点 [" + id + "] 配置无效，已跳过。");
                    failed++;
                    continue;
                }
                points.put(id.toLowerCase(), point);
                loaded++;
            }
        }
        plugin.getLogger().info("已加载 " + loaded + " 个撤离点" + (failed > 0 ? "，失败 " + failed + " 个" : "") + "。");
        return loaded;
    }

    public ExtractPoint get(String id) {
        if (id == null) return null;
        return points.get(id.toLowerCase());
    }

    /**
     * 按 WorldGuard 区域 id 查找撤离点。
     *
     * <p>若撤离点配置了 world，则必须世界一致才匹配；未配置 world 则任意世界均可匹配。
     *
     * @param regionId  区域 id（不区分大小写）
     * @param worldName 玩家所在地世界名
     * @return 匹配的撤离点，未匹配返回 null
     */
    public ExtractPoint getByRegion(String regionId, String worldName) {
        if (regionId == null) return null;
        String target = regionId.toLowerCase();
        for (ExtractPoint point : points.values()) {
            if (!point.getRegion().equalsIgnoreCase(target)) continue;
            if (point.getWorldName() != null && worldName != null
                    && !point.getWorldName().equalsIgnoreCase(worldName)) {
                continue;
            }
            return point;
        }
        return null;
    }

    /**
     * 查找所有使用指定 switchId 的撤离点。
     */
    public java.util.List<ExtractPoint> getBySwitch(String switchId) {
        java.util.List<ExtractPoint> result = new java.util.ArrayList<>();
        if (switchId == null) return result;
        for (ExtractPoint point : points.values()) {
            if (point.getSwitchId() != null && point.getSwitchId().equalsIgnoreCase(switchId)) {
                result.add(point);
            }
        }
        return result;
    }

    public Collection<ExtractPoint> getAll() {
        return points.values();
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    public int getRegionCheckInterval() {
        return Math.max(1, regionCheckInterval);
    }
}
