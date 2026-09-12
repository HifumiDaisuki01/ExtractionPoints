package com.example.extraction.listener;

import com.example.extraction.ExtractionPlugin;
import com.example.extraction.model.ExtractPoint;
import com.example.extraction.model.ExtractType;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * 玩家进出撤离点区域检测。
 *
 * <p>性能要点：只在玩家跨越方块坐标时才做 WorldGuard 区域查询，
 * 并结合 RegionQuery 的缓存，避免高频移动导致的开销。
 */
public class PlayerMoveListener implements Listener {

    private final ExtractionPlugin plugin;

    public PlayerMoveListener(ExtractionPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        // 同一方块内移动（仅朝向变化）直接跳过
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()
                && from.getWorld().equals(to.getWorld())) {
            return;
        }

        check(event.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        // 先清理被中断的玩家独立计时
        plugin.getExtractionManager().onPlayerTeleport(player);
        // 传送结束后下一个 tick 再判定区域，避免位置尚未生效
        Location to = event.getTo();
        if (to != null) {
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> check(player, player.getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        plugin.getExtractionManager().onPlayerDamage(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        plugin.getExtractionManager().onPlayerDeath(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.getExtractionManager().onPlayerQuit(event.getPlayer());
    }

    /**
     * 判定玩家当前所处撤离点，并通知管理器处理进入 / 离开事件。
     */
    private void check(Player player, Location location) {
        if (!plugin.getWorldGuardHook().isAvailable()) return;
        if (plugin.getConfigManager().isEmpty()) return;

        String previousId = plugin.getExtractionManager().getPlayerCurrentPoint(player);
        ExtractPoint matched = null;

        for (String regionId : plugin.getWorldGuardHook().getRegionIds(location)) {
            ExtractPoint candidate = plugin.getConfigManager().getByRegion(regionId, location.getWorld().getName());
            if (candidate != null && candidate.isEnabled()) {
                matched = candidate;
                break;
            }
        }

        boolean previousSame = (previousId == null && matched == null)
                || (previousId != null && matched != null && previousId.equalsIgnoreCase(matched.getId()));
        if (previousSame) return;

        plugin.getExtractionManager().onPlayerRegionChange(player, matched, previousId);
    }

    /** 供管理命令做强制刷新。 */
    public void forceCheck(Player player) {
        check(player, player.getLocation());
    }
}
