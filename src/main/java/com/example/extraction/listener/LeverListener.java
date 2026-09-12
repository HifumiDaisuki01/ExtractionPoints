package com.example.extraction.listener;

import com.example.extraction.ExtractionPlugin;
import com.example.extraction.model.ExtractPoint;
import com.example.extraction.util.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * 物理拉杆触发拉闸。
 *
 * <h3>职责</h3>
 * <ol>
 *   <li><b>绑定模式</b>：管理员执行 {@code /extract bind <switchId>} 后右键拉杆，
 *       将拉杆坐标写入该撤离点的 {@code switch.levers} 配置。</li>
 *   <li><b>拉闸触发</b>：玩家右键已绑定的拉杆时触发全局倒计时。</li>
 * </ol>
 *
 * <h3>反馈原则（重要）</h3>
 * <ul>
 *   <li>成功：给触发者本人确认消息与音效；周围玩家的警报与文本按配置半径另行发送（游戏内应然设计）。</li>
 *   <li>失败（冷却中 / 已拉闸 / 未知）：仅发送给触发者本人，绝不广播，
 *       避免暴露闸门位置或状态异常信息。</li>
 * </ul>
 */
public class LeverListener implements Listener {

    private final ExtractionPlugin plugin;

    public LeverListener(ExtractionPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.LEVER) return;

        Player player = event.getPlayer();

        // 200ms 防抖：主手/副手可能对同一交互各触发一次
        if (!plugin.tryClaimInteract(player)) return;

        Location loc = block.getLocation();

        // ---- 1. 绑定模式 ----
        String pendingSwitch = plugin.consumeBindPending(player.getUniqueId());
        if (pendingSwitch != null) {
            event.setCancelled(true);
            plugin.getBindManager().bind(player, pendingSwitch, loc);
            return;
        }

        // ---- 2. 已绑定的拉杆触发 ----
        ExtractPoint point = plugin.getConfigManager().getByLever(loc);
        if (point == null) return;

        String error = plugin.getExtractionManager().pullSwitch(point.getSwitchId(), player);
        if (error == null) {
            // 成功：周围警报由 pullSwitch 负责，这里只给触发者本人确认
            Text.send(player, plugin.getConfig(),
                    plugin.getConfig().getString("messages.switch-pulled-self", "&a闸门已开启！"),
                    "point", point.getId(),
                    "time", Text.formatTime(point.getGlobalCountdown()));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.6f);
        } else {
            // 失败：仅本人可见，且不暴露任何位置信息
            if (error.equals("messages.switch-cooldown")) {
                long remain = plugin.getSwitchManager().getCooldownRemaining(point.getSwitchId());
                Text.send(player, plugin.getConfig(),
                        plugin.getConfig().getString("messages.switch-cooldown", "&c闸门冷却中。"),
                        "time", Text.formatTime((int) remain));
            } else {
                Text.send(player, plugin.getConfig(),
                        plugin.getConfig().getString(error, "&c操作失败。"), "point", point.getId());
            }
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 0.8f);
        }
    }
}
