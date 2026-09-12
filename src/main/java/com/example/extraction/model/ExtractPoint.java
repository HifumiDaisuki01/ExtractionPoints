package com.example.extraction.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 撤离点配置模型（不可变，重载时整体重建）。
 */
public class ExtractPoint {

    private final String id;
    private final ExtractType type;
    private final String region;          // WorldGuard region id
    private final String worldName;       // 用于租界的区域（避免跨世界同名区域冲突）
    private final int countdown;          // 秒
    private final Destination destination;

    // 倒计时中断行为
    private final boolean interruptOnMoveOut;
    private final boolean interruptOnDamage;
    private final boolean interruptOnDeath;
    private final boolean interruptOnTeleport;

    // BossBar
    private final String bossBarTitle;
    private final String bossBarColor;
    private final String bossBarStyle;
    private final boolean bossBarShowGlobal;  // 拉闸撤离：是否向全服展示

    // 物品条件
    private final List<ItemCondition> items;

    // 拉闸
    private final String switchId;
    private final int globalCountdown;        // 拉闸后的全局倒计时秒数
    private final int switchCooldown;         // 两次拉闸之间的冷却秒数
    private final boolean resetSwitchAfterFinish; // 倒计时结束后自动复位闸门

    // 物理拉杆绑定：这些坐标的拉杆被右键时触发拉闸（多人地图可绑多个）
    private final List<Location> levers;

    // 拉闸后以控制台身份执行的命令（完全可选；支持 {player} {point} {switch} {time} 占位符）
    private final List<String> consoleCommands;

    // 拉闸警报（仅通知闸点周围 radius 格内的玩家）
    private final Location alarmAnchor;       // 警报锚点（闸门位置）
    private final boolean explicitAlarmLocation; // 是否在配置中明确写了 x/y/z
    private final Sound alarmSound;           // 自定义警报音效
    private final float alarmVolume;
    private final float alarmPitch;
    private final double alarmRadius;         // 覆盖半径，<=0 时使用全局默认

    // 撤离后处理
    private final boolean clearInventory;
    /** 丢包撤：撤离后只保留快捷栏/护甲/副手，清空 27 格主背包。 */
    private final boolean loseBackpack;
    private final boolean healAfterExtract;
    private final boolean feedAfterExtract;
    private final String broadcastMessage;

    // 杂项
    private final boolean enabled;
    private final String permission;          // 可选出入口权限，null 表示无

    public ExtractPoint(String id, ExtractType type, String region, String worldName, int countdown,
                        Destination destination, boolean interruptOnMoveOut, boolean interruptOnDamage,
                        boolean interruptOnDeath, boolean interruptOnTeleport, String bossBarTitle,
                        String bossBarColor, String bossBarStyle, boolean bossBarShowGlobal,
                        List<ItemCondition> items, String switchId, int globalCountdown, int switchCooldown,
                        boolean resetSwitchAfterFinish, List<Location> levers,
                        List<String> consoleCommands,
                        Location alarmAnchor, boolean explicitAlarmLocation,
                        Sound alarmSound, float alarmVolume, float alarmPitch, double alarmRadius,
                        boolean clearInventory, boolean loseBackpack, boolean healAfterExtract,
                        boolean feedAfterExtract, String broadcastMessage, boolean enabled, String permission) {
        this.id = id;
        this.type = type;
        this.region = region;
        this.worldName = worldName;
        this.countdown = countdown;
        this.destination = destination;
        this.interruptOnMoveOut = interruptOnMoveOut;
        this.interruptOnDamage = interruptOnDamage;
        this.interruptOnDeath = interruptOnDeath;
        this.interruptOnTeleport = interruptOnTeleport;
        this.bossBarTitle = bossBarTitle;
        this.bossBarColor = bossBarColor;
        this.bossBarStyle = bossBarStyle;
        this.bossBarShowGlobal = bossBarShowGlobal;
        this.items = items == null ? new ArrayList<>() : items;
        this.switchId = switchId;
        this.globalCountdown = globalCountdown;
        this.switchCooldown = switchCooldown;
        this.resetSwitchAfterFinish = resetSwitchAfterFinish;
        this.levers = levers == null ? new ArrayList<>() : levers;
        this.consoleCommands = consoleCommands == null ? new ArrayList<>() : consoleCommands;
        this.alarmAnchor = alarmAnchor;
        this.explicitAlarmLocation = explicitAlarmLocation;
        this.alarmSound = alarmSound;
        this.alarmVolume = alarmVolume;
        this.alarmPitch = alarmPitch;
        this.alarmRadius = alarmRadius;
        this.clearInventory = clearInventory;
        this.loseBackpack = loseBackpack;
        this.healAfterExtract = healAfterExtract;
        this.feedAfterExtract = feedAfterExtract;
        this.broadcastMessage = broadcastMessage;
        this.enabled = enabled;
        this.permission = permission;
    }

    /**
     * 从配置节解析一个撤离点。
     *
     * @return 解析失败返回 null
     */
    public static ExtractPoint fromConfig(String id, ConfigurationSection sec) {
        if (sec == null) return null;

        ExtractType type;
        try {
            type = ExtractType.valueOf(sec.getString("type", "NORMAL").toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }

        String region = sec.getString("region");
        if (region == null || region.isBlank()) return null;

        String worldName = sec.getString("world", null);
        int countdown = sec.getInt("countdown", 10);

        Destination dest = Destination.fromConfig(sec.getConfigurationSection("destination"));
        if (dest == null) return null;

        ConfigurationSection interrupt = sec.getConfigurationSection("interrupt");
        boolean onMoveOut = interrupt == null || interrupt.getBoolean("on-move-out", true);
        boolean onDamage = interrupt != null && interrupt.getBoolean("on-damage", false);
        boolean onDeath = interrupt == null || interrupt.getBoolean("on-death", true);
        boolean onTeleport = interrupt == null || interrupt.getBoolean("on-teleport", true);

        ConfigurationSection bar = sec.getConfigurationSection("bossbar");
        String barTitle = bar == null ? "&c撤离中... &f{time}s" : bar.getString("title", "&c撤离中... &f{time}s");
        String barColor = bar == null ? "RED" : bar.getString("color", "RED");
        String barStyle = bar == null ? "SOLID" : bar.getString("style", "SOLID");
        boolean barGlobal = bar != null && bar.getBoolean("show-global", false);

        ItemsWrapper items = parseItems(sec);

        ConfigurationSection sw = sec.getConfigurationSection("switch");
        String switchId = sw == null ? null : sw.getString("id", id);
        int globalCountdown = sw == null ? 30 : sw.getInt("global-countdown", 30);
        int switchCooldown = sw == null ? 60 : sw.getInt("cooldown", 60);
        boolean resetAfterFinish = sw == null || sw.getBoolean("reset-after-finish", true);

        // 拉闸后控制台命令（完全可选，不写则不执行）
        List<String> consoleCommands = sw == null
                ? new ArrayList<>() : new ArrayList<>(sw.getStringList("console-commands"));

        // 物理拉杆绑定列表
        List<Location> levers = new ArrayList<>();
        if (sw != null) {
            List<Map<?, ?>> rawLevers = sw.getMapList("levers");
            for (Map<?, ?> m : rawLevers) {
                Object wx = m.get("world"), xx = m.get("x"), yy = m.get("y"), zz = m.get("z");
                if (wx == null || xx == null || yy == null || zz == null) continue;
                World lw = Bukkit.getWorld(String.valueOf(wx));
                if (lw == null) continue;
                try {
                    levers.add(new Location(lw,
                            Double.parseDouble(String.valueOf(xx)),
                            Double.parseDouble(String.valueOf(yy)),
                            Double.parseDouble(String.valueOf(zz))));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        // 拉闸警报配置
        boolean explicitAnchor = hasExplicitAlarmLocation(sec, sw);
        Location anchor = parseAlarmAnchor(sec, sw, worldName, dest);
        Sound alarmSound = parseAlarmSound(sec, sw);
        float alarmVolume = (float) (sw == null ? 1.5 : sw.getDouble("alarm.volume", 1.5));
        float alarmPitch = (float) (sw == null ? 0.5 : sw.getDouble("alarm.pitch", 0.5));
        double alarmRadius = sw == null ? -1 : sw.getDouble("alarm.radius",
                sec.getDouble("alarm.radius", -1));

        ConfigurationSection after = sec.getConfigurationSection("after-extract");
        boolean clearInv = after != null && after.getBoolean("clear-inventory", false);
        // 丢包撤：支持写在 after-extract 下，也支持直接写在撤离点根节点
        boolean loseBag = (after != null && after.getBoolean("lose-backpack", false))
                || sec.getBoolean("lose-backpack", false);
        boolean heal = after == null || after.getBoolean("heal", true);
        boolean feed = after == null || after.getBoolean("feed", true);
        String broadcast = after == null ? null : after.getString("broadcast", null);

        boolean enabled = sec.getBoolean("enabled", true);
        String permission = sec.getString("permission", null);

        return new ExtractPoint(id, type, region, worldName, countdown, dest, onMoveOut, onDamage, onDeath,
                onTeleport, barTitle, barColor, barStyle, barGlobal, items.list, switchId, globalCountdown,
                switchCooldown, resetAfterFinish, levers, consoleCommands, anchor, explicitAnchor,
                alarmSound, alarmVolume, alarmPitch, alarmRadius,
                clearInv, loseBag, heal, feed, broadcast, enabled, permission);
    }

    /**
     * 判断配置中是否显式写了警报坐标（x/y/z 三项齐全）。
     */
    private static boolean hasExplicitAlarmLocation(ConfigurationSection sec, ConfigurationSection sw) {
        ConfigurationSection loc = null;
        if (sw != null) {
            loc = sw.getConfigurationSection("alarm.location");
            if (loc == null) loc = sw.getConfigurationSection("alarm");
        }
        if (loc == null) loc = sec.getConfigurationSection("alarm-anchor");
        return loc != null && loc.contains("x") && loc.contains("y") && loc.contains("z");
    }

    /**
     * 解析警报锚点。优先使用 {@code switch.alarm.location}，否则回退到 destination 的坐标（同世界时），
     * 最后回退到目标点。警报只会发给锚点周围 radius 格内的玩家。
     *
     * <p>注意：若希望警报只覆盖闸门所在世界，请显式配置 world 字段。
     */
    private static Location parseAlarmAnchor(ConfigurationSection sec, ConfigurationSection sw,
                                            String worldName, Destination dest) {
        ConfigurationSection loc = null;
        if (sw != null) {
            loc = sw.getConfigurationSection("alarm.location");
            if (loc == null) loc = sw.getConfigurationSection("alarm");
        }
        if (loc == null) loc = sec.getConfigurationSection("alarm-anchor");

        if (loc != null && loc.contains("x") && loc.contains("y") && loc.contains("z")) {
            String w = loc.getString("world", worldName != null ? worldName : dest.getWorldName());
            World world = Bukkit.getWorld(w);
            if (world != null) {
                return new Location(world, loc.getDouble("x"), loc.getDouble("y"), loc.getDouble("z"));
            }
        }
        // 回退：优先同世界，其次使用 destination 所在世界
        if (worldName != null) {
            World w = Bukkit.getWorld(worldName);
            if (w != null) return new Location(w, 0, 64, 0);
        }
        World dw = Bukkit.getWorld(dest.getWorldName());
        return dw == null ? null : new Location(dw, 0, 64, 0);
    }

    /**
     * 解析音效名。支持 Paper 的 {@code namespace:key} 形式，也支持旧版枚举名。
     */
    private static Sound parseAlarmSound(ConfigurationSection sec, ConfigurationSection sw) {
        String name = null;
        if (sw != null) name = sw.getString("alarm.sound", null);
        if (name == null) name = sec.getString("alarm.sound", null);
        if (name == null) return Sound.BLOCK_BELL_RESONATE;
        Sound s = matchSound(name);
        return s == null ? Sound.BLOCK_BELL_RESONATE : s;
    }

    @SuppressWarnings("deprecation")
    private static Sound matchSound(String name) {
        try {
            // 新版名字空间写法（如 minecraft:block.bell.resonate）
            boolean namespaced = name.contains(":");
            if (namespaced) {
                org.bukkit.NamespacedKey key =
                        org.bukkit.NamespacedKey.fromString(name.toLowerCase());
                if (key != null) {
                    Sound s = org.bukkit.Registry.SOUNDS.get(key);
                    if (s != null) return s;
                }
            }
        } catch (Throwable ignored) {
            // 忽略，继续走枚举回退
        }
        try {
            // 旧版枚举名回退：BLOCK_BELL_RESONATE / block.bell.resonate 均可
            return Sound.valueOf(name.toUpperCase().replace('.', '_').replace(':', '_'));
        } catch (Exception e) {
            return null;
        }
    }

    private static ItemsWrapper parseItems(ConfigurationSection sec) {
        List<ItemCondition> result = new ArrayList<>();
        ConfigurationSection itemSec = sec.getConfigurationSection("item");
        if (itemSec != null) {
            ItemCondition single = ItemCondition.fromConfig(itemSec);
            if (single != null) result.add(single);
        }
        List<?> rawList = sec.getList("items");
        if (rawList != null) {
            for (Object obj : rawList) {
                if (obj instanceof java.util.Map<?, ?> map) {
                    org.bukkit.configuration.MemoryConfiguration mem =
                            new org.bukkit.configuration.MemoryConfiguration();
                    for (java.util.Map.Entry<?, ?> e : map.entrySet()) {
                        mem.set(String.valueOf(e.getKey()), e.getValue());
                    }
                    ItemCondition ic = ItemCondition.fromConfig(mem);
                    if (ic != null) result.add(ic);
                }
            }
        }
        return new ItemsWrapper(result);
    }

    /** 简单包装，避免 Stream 开销。 */
    private static final class ItemsWrapper {
        final List<ItemCondition> list;

        ItemsWrapper(List<ItemCondition> list) {
            this.list = list;
        }
    }

    // ---------------- getters ----------------

    public String getId() {
        return id;
    }

    public ExtractType getType() {
        return type;
    }

    public String getRegion() {
        return region;
    }

    public String getWorldName() {
        return worldName;
    }

    public int getCountdown() {
        return countdown;
    }

    public Destination getDestination() {
        return destination;
    }

    public boolean isInterruptOnMoveOut() {
        return interruptOnMoveOut;
    }

    public boolean isInterruptOnDamage() {
        return interruptOnDamage;
    }

    public boolean isInterruptOnDeath() {
        return interruptOnDeath;
    }

    public boolean isInterruptOnTeleport() {
        return interruptOnTeleport;
    }

    public String getBossBarTitle() {
        return bossBarTitle;
    }

    public String getBossBarColor() {
        return bossBarColor;
    }

    public String getBossBarStyle() {
        return bossBarStyle;
    }

    public boolean isBossBarShowGlobal() {
        return bossBarShowGlobal;
    }

    public List<ItemCondition> getItems() {
        return items;
    }

    public boolean hasItemCondition() {
        return !items.isEmpty();
    }

    public String getSwitchId() {
        return switchId;
    }

    public int getGlobalCountdown() {
        return globalCountdown;
    }

    public int getSwitchCooldown() {
        return switchCooldown;
    }

    public boolean isResetSwitchAfterFinish() {
        return resetSwitchAfterFinish;
    }

    /** 绑定的物理拉杆坐标列表（右键即拉闸）。 */
    public List<Location> getLevers() {
        return levers;
    }

    /**
     * 拉闸成功后以控制台身份执行的命令列表。
     *
     * <p>完全可选——配置中不写 {@code switch.console-commands} 即为空，不执行任何命令。
     * 支持占位符：{@code {player}} 触发者名、{@code {point}} 撤离点 id、
     * {@code {switch}} 闸门 id、{@code {time}} 全局倒计时秒数。
     */
    public List<String> getConsoleCommands() {
        return consoleCommands;
    }

    public Location getAlarmAnchor() {
        return alarmAnchor;
    }

    /**
     * 配置中是否显式指定了警报坐标。
     * 若为 false，运行时会自动改用撤离点区域中心作为通知圆心。
     */
    public boolean hasExplicitAlarmLocation() {
        return explicitAlarmLocation;
    }

    public Sound getAlarmSound() {
        return alarmSound;
    }

    public float getAlarmVolume() {
        return alarmVolume;
    }

    public float getAlarmPitch() {
        return alarmPitch;
    }

    /**
     * 本撤离点的警报覆盖半径。返回值 &lt;= 0 表示使用全局配置 {@code settings.notification.radius}。
     */
    public double getAlarmRadius() {
        return alarmRadius;
    }

    public boolean isClearInventory() {
        return clearInventory;
    }

    /**
     * 是否为「丢包撤」。
     *
     * <p>启用后撤离时只保留玩家身上的快捷栏（9 格）、护甲（4 格）与副手（1 格），
     * 主背包的 27 格会被全部清空。与撤离类型无关，NORMAL / ITEM / SWITCH 均可使用。
     */
    public boolean isLoseBackpack() {
        return loseBackpack;
    }

    public boolean isHealAfterExtract() {
        return healAfterExtract;
    }

    public boolean isFeedAfterExtract() {
        return feedAfterExtract;
    }

    public String getBroadcastMessage() {
        return broadcastMessage;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getPermission() {
        return permission;
    }
}
