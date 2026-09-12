package com.example.extraction.manager;

import com.example.extraction.ExtractionPlugin;
import com.example.extraction.model.ExtractPoint;
import com.example.extraction.model.ExtractType;
import com.example.extraction.util.Text;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 拉杆绑定管理器：把世界里的拉杆坐标持久化到 config.yml。
 *
 * <p>写入路径：{@code extraction-points.<id>.switch.levers}
 * 格式：{@code - {world: ..., x: ..., y: ..., z: ...}}
 */
public class BindManager {

    private final ExtractionPlugin plugin;

    public BindManager(ExtractionPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 将拉杆坐标绑定到指定闸门（写入配置并热重载）。
     *
     * @param player   触发绑定的管理员（结果仅发给本人）
     * @param switchId 闸门编号
     * @param lever    拉杆方块坐标
     */
    public void bind(Player player, String switchId, Location lever) {
        ExtractPoint point = null;
        for (ExtractPoint p : plugin.getConfigManager().getAll()) {
            if (p.getType() == ExtractType.SWITCH
                    && p.getSwitchId() != null
                    && p.getSwitchId().equalsIgnoreCase(switchId)) {
                point = p;
                break;
            }
        }
        if (point == null) {
            Text.send(player, plugin.getConfig(),
                    "&c未找到拉闸类型撤离点（switchId=&f" + switchId + "&c）。");
            return;
        }

        FileConfiguration cfg = plugin.getConfig();
        String path = "extraction-points." + point.getId() + ".switch.levers";

        List<Map<?, ?>> existing = cfg.getMapList(path);
        // 去重：同一坐标不重复绑定
        for (Map<?, ?> m : existing) {
            if (sameBlock(m, lever)) {
                Text.send(player, plugin.getConfig(),
                        "&e该拉杆已绑定过 &f" + point.getId() + "&e，无需重复绑定。");
                return;
            }
        }

        List<Object> updated = new ArrayList<>(existing);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("world", lever.getWorld().getName());
        entry.put("x", lever.getBlockX());
        entry.put("y", lever.getBlockY());
        entry.put("z", lever.getBlockZ());
        updated.add(entry);
        cfg.set(path, updated);
        plugin.saveConfig();

        // 热重载，让新拉杆立即生效
        plugin.getConfigManager().load();

        int total = plugin.getConfigManager().get(point.getId()).getLevers().size();
        Text.send(player, plugin.getConfig(),
                "&a绑定成功！&f[&e" + lever.getBlockX() + ", " + lever.getBlockY() + ", "
                        + lever.getBlockZ() + "&f] &7(" + lever.getWorld().getName() + ")"
                        + " &a→ 闸门 &f" + point.getSwitchId()
                        + " &7(该撤离点已绑 " + total + " 个拉杆)");
        Text.send(player, plugin.getConfig(),
                "&7如需继续绑定其他拉杆，再次执行 &f/extract bind " + point.getSwitchId()
                        + "&7 即可。");
    }

    private boolean sameBlock(Map<?, ?> m, Location loc) {
        try {
            return String.valueOf(m.get("world")).equals(loc.getWorld().getName())
                    && Integer.parseInt(String.valueOf(m.get("x"))) == loc.getBlockX()
                    && Integer.parseInt(String.valueOf(m.get("y"))) == loc.getBlockY()
                    && Integer.parseInt(String.valueOf(m.get("z"))) == loc.getBlockZ();
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
