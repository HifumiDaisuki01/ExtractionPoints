package com.example.extraction.manager;

import com.example.extraction.ExtractionPlugin;
import com.example.extraction.model.ExtractPoint;
import com.example.extraction.model.ExtractType;
import com.example.extraction.model.ItemCondition;
import com.example.extraction.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

/**
 * 撤离点运行时管理器。
 *
 * <h3>三类撤离点的计时模型</h3>
 * <ul>
 *   <li><b>NORMAL / ITEM</b>：玩家独立计时。以 playerUUID 为键，进入开始，离开清零。
 *       反复进出互不影响，因为每个玩家的计时器相互独立。</li>
 *   <li><b>SWITCH</b>：全局计时。以 switchId 为键，一旦拉闸就开始一个绝对时间轴上的倒计时。
 *       玩家进出区域只影响 BossBar 的显示与隐藏，<b>不影响</b>倒计时本身。
 *       倒计时归零时，所有仍在区域内的玩家被撤离；倒计时结束即停止，需要重新拉闸。
 *       计时使用「绝对结束时间戳」而非递减计数器，即使任务被延迟也不会累积误差。</li>
 * </ul>
 */
public class ExtractionManager {

    private final ExtractionPlugin plugin;

    /** 玩家独立计时：playerUUID -> 该玩家正在计时的撤离点 id */
    private final Map<UUID, String> playerTimers = new HashMap<>();
    /** 玩家独立计时的剩余秒数：playerUUID -> 剩余秒 */
    private final Map<UUID, Integer> playerRemaining = new HashMap<>();
    /** 玩家当前显示的 BossBar */
    private final Map<UUID, BossBar> playerBars = new HashMap<>();

    /** 全局（拉闸）倒计时：switchId -> 状态 */
    private final Map<String, GlobalTimer> globalTimers = new HashMap<>();
    /** 玩家正在观看的全局撤离 BossBar：playerUUID -> switchId */
    private final Map<UUID, String> globalBarViewer = new HashMap<>();

    /** 每个玩家当前所处的撤离点：playerUUID -> pointId（统一小写） */
    private final Map<UUID, String> playerInPoint = new HashMap<>();

    private BukkitTask tickTask;

    public ExtractionManager(ExtractionPlugin plugin) {
        this.plugin = plugin;
    }

    // ==================================================================
    //  生命周期
    // ==================================================================

    public void start() {
        stop();
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (Map.Entry<UUID, BossBar> e : new HashMap<>(playerBars).entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) p.hideBossBar(e.getValue());
        }
        for (Map.Entry<UUID, String> e : new HashMap<>(globalBarViewer).entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            GlobalTimer t = globalTimers.get(e.getValue());
            if (p != null && t != null && t.bossBar != null) p.hideBossBar(t.bossBar);
        }
        playerBars.clear();
        globalBarViewer.clear();
        playerTimers.clear();
        playerRemaining.clear();
        globalTimers.clear();
        playerInPoint.clear();
    }

    // ==================================================================
    //  每秒主循环
    // ==================================================================

    private void tick() {
        tickPlayerTimers();
        tickGlobalTimers();
    }

    /** 驱动 NORMAL / ITEM 的玩家独立计时。 */
    private void tickPlayerTimers() {
        // 物品撤离补检：玩家在区域内但尚未开始计时（进场时缺物品），
        // 每秒复查背包，一旦携带所需物品立即开始计时。
        for (Map.Entry<UUID, String> e : new HashMap<>(playerInPoint).entrySet()) {
            UUID uuid = e.getKey();
            if (playerTimers.containsKey(uuid)) continue;
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;
            ExtractPoint point = plugin.getConfigManager().get(e.getValue());
            if (point == null || !point.isEnabled() || point.getType() != ExtractType.ITEM) continue;
            if (player.hasPermission("extraction.command.bypass")
                    || checkItemCondition(player, point, false)) {
                playerTimers.put(uuid, point.getId());
                playerRemaining.put(uuid, point.getCountdown());
                showPlayerBar(player, point);
                sendMessage(player, "messages.entered-area", "point", point.getId(),
                        "time", Text.formatTime(point.getCountdown()));
            }
        }

        for (UUID uuid : new HashSet<>(playerTimers.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            String pointId = playerTimers.get(uuid);
            ExtractPoint point = pointId == null ? null : plugin.getConfigManager().get(pointId);

            if (player == null || !player.isOnline() || point == null || !point.isEnabled()
                    || point.getType() == ExtractType.SWITCH) {
                cancelPlayerTimer(player, point);
                continue;
            }

            int remain = playerRemaining.getOrDefault(uuid, point.getCountdown()) - 1;
            if (remain <= 0) {
                if (point.hasItemCondition()
                        && !checkItemCondition(player, point, false)
                        && !player.hasPermission("extraction.command.bypass")) {
                    sendMessage(player, "messages.missing-item", "item",
                            point.getItems().isEmpty() ? "-" : point.getItems().get(0).toString());
                } else {
                    completeExtraction(player, point);
                }
                cancelPlayerTimer(player, point);
                continue;
            }

            playerRemaining.put(uuid, remain);
            updatePlayerBar(player, point, remain);
        }
    }

    /** 驱动 SWITCH 的全局倒计时。 */
    private void tickGlobalTimers() {
        for (Map.Entry<String, GlobalTimer> entry : new HashMap<>(globalTimers).entrySet()) {
            GlobalTimer timer = entry.getValue();
            if (timer.finished) continue;

            // 绝对时间戳计算剩余，杜绝任务漂移
            long remainMillis = timer.endTime - System.currentTimeMillis();
            int remainSeconds = (int) Math.ceil(remainMillis / 1000.0);

            if (remainSeconds <= 0) {
                finishGlobalTimer(entry.getKey(), timer);
                continue;
            }

            if (remainSeconds != timer.remainingSeconds) {
                timer.remainingSeconds = remainSeconds;
                updateGlobalBars(timer);
                // 最后 5 秒播报提示音
                if (remainSeconds <= 5) {
                    playToGlobalViewers(timer, Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1.8f);
                }
            }
        }
    }

    private void finishGlobalTimer(String switchId, GlobalTimer timer) {
        timer.finished = true;
        timer.remainingSeconds = 0;

        ExtractPoint point = plugin.getConfigManager().get(timer.pointId);
        if (point != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!plugin.getWorldGuardHook().isPlayerIn(player, point)) continue;
                if (point.hasItemCondition()
                        && !checkItemCondition(player, point, false)
                        && !player.hasPermission("extraction.command.bypass")) {
                    sendMessage(player, "messages.missing-item", "item",
                            point.getItems().isEmpty() ? "-" : point.getItems().get(0).toString());
                } else {
                    completeExtraction(player, point);
                }
            }
        }

        // 隐藏所有观看者的 BossBar
        for (Map.Entry<UUID, String> e : new HashMap<>(globalBarViewer).entrySet()) {
            if (!e.getValue().equals(switchId)) continue;
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && timer.bossBar != null) p.hideBossBar(timer.bossBar);
            globalBarViewer.remove(e.getKey());
        }

        // 自动复位闸门，允许下次拉闸
        if (point == null || point.isResetSwitchAfterFinish()) {
            plugin.getSwitchManager().setActive(switchId, false);
        }
        globalTimers.remove(switchId);

        if (point != null) {
            // 结束通知同样只给附近玩家
            notifyNearby(point, "messages.global-countdown-finished",
                    "point", point.getId());
        }
    }

    // ==================================================================
    //  玩家进入 / 离开撤离点
    // ==================================================================

    /**
     * 由监听器调用：玩家所处撤离点发生变化。
     *
     * @param player     玩家
     * @param point      玩家现在进入的撤离点；null 表示离开了所有撤离点
     * @param previousId 上一次所在的撤离点 id（可为 null）
     */
    public void onPlayerRegionChange(Player player, ExtractPoint point, String previousId) {
        String newId = point == null ? null : point.getId();

        String oldId = playerInPoint.get(player.getUniqueId());
        boolean same = (oldId == null && newId == null) || (oldId != null && oldId.equals(newId));
        if (same) return;

        if (newId == null) {
            playerInPoint.remove(player.getUniqueId());
        } else {
            playerInPoint.put(player.getUniqueId(), newId);
        }

        // 处理离开旧点
        if (oldId != null && !oldId.equals(newId)) {
            ExtractPoint old = plugin.getConfigManager().get(oldId);
            if (old != null) {
                if (old.getType() == ExtractType.SWITCH) {
                    hideGlobalBar(player, old);
                } else if (old.isInterruptOnMoveOut()) {
                    cancelPlayerTimer(player, old);
                    sendMessage(player, "messages.left-area", "point", old.getId());
                }
            }
        }

        if (point == null || !point.isEnabled()) return;
        if (point.getPermission() != null && !player.hasPermission(point.getPermission())) return;

        switch (point.getType()) {
            case NORMAL, ITEM -> onEnterPlayerTimer(player, point);
            case SWITCH -> onEnterGlobal(player, point);
            default -> {
            }
        }
    }

    private void onEnterPlayerTimer(Player player, ExtractPoint point) {
        // 物品撤离点：进入时未携带所需物品 → 不开始计时、不显示 BossBar，
        // 玩家在区域内补到物品后由 tick 补检自动开始（messages.entered-area 此时提示）。
        boolean bypass = player.hasPermission("extraction.command.bypass");
        if (point.hasItemCondition() && !bypass && !checkItemCondition(player, point, false)) {
            sendMessage(player, "messages.missing-item", "item",
                    point.getItems().isEmpty() ? "-" : point.getItems().get(0).toString());
            return;
        }

        // 进入即重新计时（离开已清零，反复进出不会错误累积时间）
        playerTimers.put(player.getUniqueId(), point.getId());
        playerRemaining.put(player.getUniqueId(), point.getCountdown());

        showPlayerBar(player, point);
        sendMessage(player, "messages.entered-area", "point", point.getId(),
                "time", Text.formatTime(point.getCountdown()));
    }

    private void onEnterGlobal(Player player, ExtractPoint point) {
        String switchId = point.getSwitchId();
        GlobalTimer timer = globalTimers.get(switchId);
        boolean active = plugin.getSwitchManager().isActive(switchId);

        if (timer != null && !timer.finished) {
            // 倒计时进行中：只显示 BossBar，绝不重置剩余时间
            showGlobalBar(player, point, timer);
            sendMessage(player, "messages.enter-active-countdown",
                    "point", point.getId(), "time", Text.formatTime(timer.remainingSeconds));
        } else if (!active) {
            sendMessage(player, "messages.switch-not-pulled", "point", point.getId());
        } else {
            // 闸已拉但计时器缺失（如插件重载后补建）
            startGlobalTimer(point);
        }
    }

    private void hideGlobalBar(Player player, ExtractPoint point) {
        GlobalTimer timer = globalTimers.get(point.getSwitchId());
        globalBarViewer.remove(player.getUniqueId());
        if (timer != null && timer.bossBar != null) player.hideBossBar(timer.bossBar);
    }

    // ==================================================================
    //  拉闸
    // ==================================================================

    /**
     * 拉闸：为指定 switchId 启动全局倒计时。
     *
     * @return 成功返回 null，失败返回消息键
     */
    public String pullSwitch(String switchId, Player operator) {
        ExtractPoint point = findPointBySwitch(switchId);
        if (point == null) return "messages.switch-unknown";

        SwitchManager sm = plugin.getSwitchManager();
        if (sm.getCooldownRemaining(switchId) > 0) return "messages.switch-cooldown";

        GlobalTimer existing = globalTimers.get(switchId);
        if (existing != null && !existing.finished) return "messages.switch-already-active";

        sm.setActive(switchId, true);
        sm.setCooldown(switchId, point.getSwitchCooldown());
        startGlobalTimer(point);

        // ★ 拉闸通知：只发给闸点周围 radius 格内的玩家
        notifyNearby(point, "messages.switch-pulled",
                "point", point.getId(),
                "player", operator == null ? "系统" : operator.getName(),
                "time", Text.formatTime(point.getGlobalCountdown()));

        // ★ 警报声：只对范围内的玩家播放
        playNearby(point, point.getAlarmSound(), point.getAlarmVolume(), point.getAlarmPitch());

        // ★ 拉闸后控制台命令（可选）：替换占位符后以控制台身份执行
        runSwitchCommands(point, operator);

        return null;
    }

    /**
     * 执行撤离点配置的拉闸命令（switch.console-commands）。
     *
     * <p>占位符：{player}=触发者名（控制台触发时为 CONSOLE）、{point}=撤离点 id、
     * {switch}=闸门 id、{time}=全局倒计时秒数。
     * 任何一条命令执行出错都不影响后续命令与拉闸流程。
     */
    private void runSwitchCommands(ExtractPoint point, Player operator) {
        if (point.getConsoleCommands().isEmpty()) return;
        String playerName = operator == null ? "CONSOLE" : operator.getName();
        for (String raw : point.getConsoleCommands()) {
            if (raw == null || raw.isBlank()) continue;
            String cmd = Text.replace(raw,
                    "player", playerName,
                    "point", point.getId(),
                    "switch", point.getSwitchId(),
                    "time", String.valueOf(point.getGlobalCountdown()));
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                plugin.getLogger().info("拉闸命令 [" + point.getId() + "] 已执行: " + cmd);
            } catch (Throwable t) {
                plugin.getLogger().warning("拉闸命令执行失败 [" + point.getId() + "]: "
                        + cmd + " -> " + t.getMessage());
            }
        }
    }

    /** 直接为某撤离点启动全局倒计时。 */
    public void startGlobalTimer(ExtractPoint point) {
        GlobalTimer timer = new GlobalTimer();
        timer.pointId = point.getId();
        timer.switchId = point.getSwitchId();
        timer.remainingSeconds = point.getGlobalCountdown();
        timer.endTime = System.currentTimeMillis() + point.getGlobalCountdown() * 1000L;
        timer.bossBar = BossBar.bossBar(
                Text.color(renderBarTitle(point, point.getGlobalCountdown())),
                1.0f,
                parseColor(point.getBossBarColor()),
                parseStyle(point.getBossBarStyle()));
        timer.finished = false;

        GlobalTimer old = globalTimers.remove(point.getSwitchId());
        if (old != null && old.bossBar != null) {
            for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(old.bossBar);
        }
        globalTimers.put(point.getSwitchId(), timer);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.getWorldGuardHook().isPlayerIn(player, point)) {
                showGlobalBar(player, point, timer);
            }
        }
    }

    /** 停止某撤离点的全局倒计时（管理命令用）。 */
    public boolean stopGlobalTimer(String switchId) {
        GlobalTimer timer = globalTimers.remove(switchId);
        if (timer == null) return false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (timer.bossBar != null) p.hideBossBar(timer.bossBar);
        }
        globalBarViewer.entrySet().removeIf(e -> e.getValue().equals(switchId));
        plugin.getSwitchManager().setActive(switchId, false);
        return true;
    }

    private ExtractPoint findPointBySwitch(String switchId) {
        for (ExtractPoint p : plugin.getConfigManager().getAll()) {
            if (p.getType() == ExtractType.SWITCH && switchId.equalsIgnoreCase(p.getSwitchId())) {
                return p;
            }
        }
        return null;
    }

    // ==================================================================
    //  完成撤离
    // ==================================================================

    /**
     * 执行一次撤离：条件校验 → 扣除物品 → 传送 → 后处理。
     */
    public boolean completeExtraction(Player player, ExtractPoint point) {
        return completeExtraction(player, point, false);
    }

    /**
     * @param ignoreConditions true 时不校验也不消耗物品（管理命令 /extract force 使用）
     */
    public boolean completeExtraction(Player player, ExtractPoint point, boolean ignoreConditions) {
        boolean bypass = ignoreConditions || player.hasPermission("extraction.command.bypass");

        // 1. 最终条件校验（含物品消耗）
        if (point.hasItemCondition() && !bypass) {
            if (!checkItemCondition(player, point, false)) {
                sendMessage(player, "messages.missing-item", "item",
                        point.getItems().isEmpty() ? "-" : point.getItems().get(0).toString());
                return false;
            }
        }

        // 2. 传送
        Location dest = point.getDestination().toLocation();
        if (dest == null) {
            plugin.getLogger().warning("撤离点 [" + point.getId() + "] 的目标世界 "
                    + point.getDestination().getWorldName() + " 未加载，撤离失败。");
            sendMessage(player, "messages.destination-unloaded");
            return false;
        }

        cleanupPlayer(player, point);
        player.teleport(dest);

        // 3. 扣除物品（传送成功后再扣，避免扣了没传）
        if (point.hasItemCondition() && !bypass) {
            checkItemCondition(player, point, true);
        }

        // 4. 后处理
        if (point.isClearInventory()) {
            // 全部清空，包括护甲
            player.getInventory().clear();
            player.getInventory().setArmorContents(null);
        } else if (point.isLoseBackpack()) {
            // 丢包撤：只清空主背包 27 格（槽位 9~35），
            // 保留快捷栏 0~8、护甲、副手
            applyLoseBackpack(player);
        }
        if (point.isHealAfterExtract()) {
            player.setHealth(player.getMaxHealth());
            player.setFoodLevel(20);
            player.setSaturation(20f);
        }
        if (point.isFeedAfterExtract()) {
            player.setFireTicks(0);
        }

        // 5. 提示与音效
        sendMessage(player, "messages.extract-success", "point", point.getId(),
                "world", dest.getWorld().getName());
        if (point.isLoseBackpack() && !point.isClearInventory()) {
            sendMessage(player, "messages.lose-backpack", "point", point.getId());
        }
        float sucessVolume = (float) plugin.getConfig().getDouble("settings.extract-sound.volume", 1.0);
        float successPitch = (float) plugin.getConfig().getDouble("settings.extract-sound.pitch", 1.4);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, sucessVolume, successPitch);

        if (point.getBroadcastMessage() != null && !point.getBroadcastMessage().isBlank()) {
            Bukkit.broadcast(Text.color(Text.replace(point.getBroadcastMessage(),
                    "player", player.getName(), "point", point.getId())));
        } else {
            String tpl = plugin.getConfig().getString("settings.broadcast-extract-message", null);
            if (tpl != null) {
                Bukkit.broadcast(Text.color(Text.replace(tpl,
                        "player", player.getName(), "point", point.getId())));
            }
        }

        plugin.getLogger().info("玩家 " + player.getName() + " 从撤离点 " + point.getId() + " 成功撤离。");
        return true;
    }

    /**
     * 执行「丢包撤」的物品处理。
     *
     * <p>Bukkit 玩家背包槽位布局：
     * <pre>
     *   0 ~  8 : 快捷栏（保留）
     *   9 ~ 35 : 主背包 27 格（清空）
     *   36~ 39 : 护甲（保留）
     *      40  : 副手（保留）
     * </pre>
     *
     * <p>本方法只清空主背包区域，快捷栏、护甲与副手原样保留。
     * 同时会把光标上（拖拽中）的物品一并清空，避免玩家通过拖拽绕过限制。
     */
    private void applyLoseBackpack(Player player) {
        PlayerInventory inv = player.getInventory();
        int cleared = 0;
        for (int slot = 9; slot <= 35; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack != null && stack.getType() != Material.AIR) {
                cleared += stack.getAmount();
                inv.setItem(slot, null);
            }
        }
        // 清掉光标上的物品，防止拖拽规避
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && cursor.getType() != Material.AIR) {
            player.setItemOnCursor(null);
        }
        player.updateInventory();
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 0.7f);
        plugin.getLogger().info("玩家 " + player.getName() + " 触发丢包撤，清空主背包 " + cleared + " 个物品。");
    }

    private void cleanupPlayer(Player player, ExtractPoint point) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        playerTimers.remove(uuid);
        playerRemaining.remove(uuid);
        BossBar bar = playerBars.remove(uuid);
        if (bar != null && player.isOnline()) player.hideBossBar(bar);
        playerInPoint.remove(uuid);

        if (point != null && point.getType() == ExtractType.SWITCH) {
            GlobalTimer timer = globalTimers.get(point.getSwitchId());
            globalBarViewer.remove(uuid);
            if (timer != null && timer.bossBar != null && player.isOnline()) {
                player.hideBossBar(timer.bossBar);
            }
        }
    }

    /**
     * 检查（并可选择消耗）撤离所需物品。
     *
     * @param consume true 表示真正扣除
     */
    public boolean checkItemCondition(Player player, ExtractPoint point, boolean consume) {
        if (!point.hasItemCondition()) return true;
        for (ItemCondition cond : point.getItems()) {
            if (!hasItem(player, cond)) return false;
        }
        if (!consume) return true;
        for (ItemCondition cond : point.getItems()) {
            if (cond.isConsume()) removeItem(player, cond);
        }
        return true;
    }

    private boolean hasItem(Player player, ItemCondition cond) {
        int found = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (!matches(stack, cond)) continue;
            found += stack.getAmount();
            if (found >= cond.getAmount()) return true;
        }
        return false;
    }

    private void removeItem(Player player, ItemCondition cond) {
        int remaining = cond.getAmount();
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (!matches(stack, cond)) continue;
            int take = Math.min(stack.getAmount(), remaining);
            stack.setAmount(stack.getAmount() - take);
            remaining -= take;
            if (stack.getAmount() <= 0) player.getInventory().setItem(i, null);
        }
        player.updateInventory();
    }

    private boolean matches(ItemStack stack, ItemCondition cond) {
        if (stack == null || stack.getType() != cond.getMaterial()) return false;
        if (cond.getNameContains() == null) return true;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return false;
        String display = LegacyComponentSerializer.legacySection().serialize(meta.displayName());
        return Text.strip(display).contains(cond.getNameContains());
    }

    // ==================================================================
    //  BossBar
    // ==================================================================

    private void showPlayerBar(Player player, ExtractPoint point) {
        int remain = playerRemaining.getOrDefault(player.getUniqueId(), point.getCountdown());
        BossBar bar = playerBars.get(player.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(
                    Text.color(renderBarTitle(point, remain)),
                    1.0f,
                    parseColor(point.getBossBarColor()),
                    parseStyle(point.getBossBarStyle()));
            playerBars.put(player.getUniqueId(), bar);
            player.showBossBar(bar);
        } else {
            bar.name(Text.color(renderBarTitle(point, remain)));
        }
        bar.progress(clamp(remain / (float) Math.max(1, point.getCountdown())));
    }

    private void updatePlayerBar(Player player, ExtractPoint point, int remain) {
        BossBar bar = playerBars.get(player.getUniqueId());
        if (bar == null || !player.isOnline()) {
            showPlayerBar(player, point);
            return;
        }
        bar.name(Text.color(renderBarTitle(point, remain)));
        bar.progress(clamp(remain / (float) Math.max(1, point.getCountdown())));
    }

    private void showGlobalBar(Player player, ExtractPoint point, GlobalTimer timer) {
        if (timer.bossBar == null) return;
        player.showBossBar(timer.bossBar);
        globalBarViewer.put(player.getUniqueId(), point.getSwitchId());
    }

    private void updateGlobalBars(GlobalTimer timer) {
        if (timer.bossBar == null) return;
        ExtractPoint point = plugin.getConfigManager().get(timer.pointId);
        if (point == null) return;
        timer.bossBar.name(Text.color(renderBarTitle(point, timer.remainingSeconds)));
        timer.bossBar.progress(clamp(timer.remainingSeconds / (float) Math.max(1, point.getGlobalCountdown())));
    }

    private void playToGlobalViewers(GlobalTimer timer, Sound sound, float volume, float pitch) {
        for (Map.Entry<UUID, String> e : globalBarViewer.entrySet()) {
            if (!e.getValue().equals(timer.switchId)) continue;
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) p.playSound(p.getLocation(), sound, volume, pitch);
        }
    }

    private String renderBarTitle(ExtractPoint point, int seconds) {
        return Text.replace(point.getBossBarTitle(),
                "time", Text.formatTime(seconds),
                "seconds", String.valueOf(seconds),
                "point", point.getId());
    }

    private float clamp(float f) {
        return Math.max(0f, Math.min(1f, f));
    }

    private BossBar.Color parseColor(String name) {
        try {
            return BossBar.Color.valueOf(name.toUpperCase());
        } catch (Exception e) {
            return BossBar.Color.RED;
        }
    }

    private BossBar.Overlay parseStyle(String name) {
        try {
            return BossBar.Overlay.valueOf(name.toUpperCase());
        } catch (Exception e) {
            return BossBar.Overlay.PROGRESS;
        }
    }

    // ==================================================================
    //  中断
    // ==================================================================

    public void onPlayerDamage(Player player) {
        String id = playerTimers.get(player.getUniqueId());
        if (id == null) return;
        ExtractPoint point = plugin.getConfigManager().get(id);
        if (point == null || !point.isInterruptOnDamage()) return;
        cancelPlayerTimer(player, point);
        sendMessage(player, "messages.interrupted-damage", "point", point.getId());
    }

    public void onPlayerDeath(Player player) {
        String id = playerTimers.get(player.getUniqueId());
        ExtractPoint point = id == null ? null : plugin.getConfigManager().get(id);
        if (point != null && point.isInterruptOnDeath()) {
            cancelPlayerTimer(player, point);
        }
        cleanupPlayer(player, point);
    }

    public void onPlayerTeleport(Player player) {
        String id = playerTimers.get(player.getUniqueId());
        if (id == null) return;
        ExtractPoint point = plugin.getConfigManager().get(id);
        if (point == null || !point.isInterruptOnTeleport()) return;
        cancelPlayerTimer(player, point);
        sendMessage(player, "messages.interrupted-teleport", "point", point.getId());
    }

    public void onPlayerQuit(Player player) {
        String id = playerTimers.get(player.getUniqueId());
        ExtractPoint point = id == null ? null : plugin.getConfigManager().get(id);
        if (point != null) cancelPlayerTimer(player, point);
        cleanupPlayer(player, point);
    }

    private void cancelPlayerTimer(Player player, ExtractPoint point) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        playerTimers.remove(uuid);
        playerRemaining.remove(uuid);
        if (point != null) playerInPoint.remove(uuid);
        BossBar bar = playerBars.remove(uuid);
        if (bar != null && player.isOnline()) player.hideBossBar(bar);
    }

    // ==================================================================
    //  附近通知（拉闸警报）
    // ==================================================================

    /**
     * 向撤离点锚点周围半径内的玩家发送通知消息。
     *
     * <p>半径取自配置 {@code broadcast-radius}，默认 300 格。
     * 不会跨世界广播，也不会通知全服其他地图的玩家。
     *
     * @param point    撤离点（以其 destination 作为锚点；若同世界则优先用区域中心）
     * @param messageKey 消息键，如 messages.switch-pulled
     * @param pairs    占位符键值对
     */
    public void notifyNearby(ExtractPoint point, String messageKey, Object... pairs) {
        String raw = plugin.getConfig().getString(messageKey, null);
        if (raw == null || raw.isBlank()) return;

        Location anchor = resolveAnchor(point);
        if (anchor == null || anchor.getWorld() == null) return;

        double radius = resolveRadius(point);
        double r2 = radius * radius;

        String prefix = plugin.getConfig().getString("messages.prefix", "&8[&c撤离&8] &r");
        String composed = Text.replace(prefix + raw, pairs);

        for (Player p : anchor.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(anchor) > r2) continue;
            p.sendMessage(Text.color(composed));
        }
    }

    /**
     * 向撤离点锚点周围半径内的玩家播放音效。
     */
    public void playNearby(ExtractPoint point, Sound sound, float volume, float pitch) {
        if (sound == null) return;
        Location anchor = resolveAnchor(point);
        if (anchor == null || anchor.getWorld() == null) return;

        double radius = resolveRadius(point);
        double r2 = radius * radius;

        for (Player p : anchor.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(anchor) > r2) continue;
            p.playSound(p.getLocation(), sound, volume, pitch);
        }
    }

    /**
     * 解析生效的通知半径：撤离点自身配置优先，否则使用全局默认。
     */
    private double resolveRadius(ExtractPoint point) {
        double r = point.getAlarmRadius();
        if (r <= 0) {
            r = plugin.getConfig().getDouble("settings.notification.radius", 300.0);
        }
        return Math.max(1.0, r);
    }

    /**
     * 解析通知/音效的圆心。
     *
     * <p>优先级：配置的 {@code alarm.location} → 撤离点区域中心 → 目标点所在世界。
     * 这样即便管理员忘记填坐标，通知也不会错误地以 (0,64,0) 为圆心。
     */
    private Location resolveAnchor(ExtractPoint point) {
        Location anchor = point.getAlarmAnchor();
        // 若配置了明确的 x/y/z，直接使用
        if (anchor != null && point.hasExplicitAlarmLocation()) {
            return anchor;
        }
        // 尝试用区域中心
        String worldName = point.getWorldName() != null
                ? point.getWorldName() : point.getDestination().getWorldName();
        Location center = plugin.getWorldGuardHook().getRegionCenter(worldName, point.getRegion());
        if (center != null) return center;
        return anchor;
    }

    // ==================================================================
    //  状态查询（供命令使用）
    // ==================================================================

    public int getPlayerRemaining(Player player) {
        return playerRemaining.getOrDefault(player.getUniqueId(), -1);
    }

    public String getPlayerTimerPoint(Player player) {
        return playerTimers.get(player.getUniqueId());
    }

    public boolean isGlobalActive(String switchId) {
        GlobalTimer t = globalTimers.get(switchId);
        return t != null && !t.finished;
    }

    public int getGlobalRemaining(String switchId) {
        GlobalTimer t = globalTimers.get(switchId);
        return t == null || t.finished ? -1 : t.remainingSeconds;
    }

    public String getPlayerCurrentPoint(Player player) {
        return playerInPoint.get(player.getUniqueId());
    }

    // ==================================================================
    //  内部类
    // ==================================================================

    /** 全局（拉闸）倒计时状态。 */
    public static class GlobalTimer {
        public String pointId;
        public String switchId;
        public long endTime;
        public int remainingSeconds;
        public BossBar bossBar;
        public boolean finished;
    }

    /** 统一发送带前缀的消息。 */
    private void sendMessage(Player player, String key, Object... pairs) {
        if (player == null || !player.isOnline()) return;
        String raw = plugin.getConfig().getString(key, null);
        if (raw == null || raw.isBlank()) return;
        String prefix = plugin.getConfig().getString("messages.prefix", "&8[&c撤离&8] &r");
        player.sendMessage(Text.color(Text.replace(prefix + raw, pairs)));
    }

    /** 全服广播（仅用于撤离成功等无需限范围的提示）。 */
    private void broadcast(String key, Object... pairs) {
        String raw = plugin.getConfig().getString(key, null);
        if (raw == null || raw.isBlank()) return;
        String prefix = plugin.getConfig().getString("messages.prefix", "&8[&c撤离&8] &r");
        Bukkit.broadcast(Text.color(Text.replace(prefix + raw, pairs)));
    }
}
