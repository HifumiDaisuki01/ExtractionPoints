package com.example.extraction.util;

import com.example.extraction.model.ExtractPoint;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * WorldGuard 挂钩。
 *
 * <p>核心职责：判断玩家当前所在的 WorldGuard 区域中有哪些是已注册的撤离点。
 *
 * <p>性能要点：
 * <ul>
 *   <li>只在玩家移动跨越方块时才调用（由监听器保证）</li>
 *   <li>使用 RegionQuery 的内部查询缓存（1~2 秒），避免高频重算</li>
 * </ul>
 *
 * <p>本类通过「惰性加载 + 异常兜底」实现软依赖：即使服务器未安装 WorldGuard，
 * 插件仍然能够正常启用，只是撤离点功能不可用。
 */
public class WorldGuardHook {

    private final Logger logger;
    private boolean available = false;

    // WorldGuard 类型（通过 Class.forName 加载，避免编译/运行期强依赖）
    private Class<?> worldGuardClass;
    private Class<?> bukkitAdapterClass;
    private Class<?> regionContainerClass;
    private Class<?> regionQueryClass;
    private Class<?> applicableRegionSetClass;
    private Class<?> protectedRegionClass;

    // 缓存实例，避免每次查询重复反射
    private Object worldGuardInstance;

    public WorldGuardHook(Logger logger) {
        this.logger = logger;
    }

    /**
     * 尝试挂钩 WorldGuard。失败不抛异常，只标记不可用。
     *
     * @return 是否挂钩成功
     */
    public boolean hook() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("WorldGuard");
        if (plugin == null || !plugin.isEnabled()) {
            logger.warning("未检测到 WorldGuard，撤离点功能将不可用。");
            available = false;
            return false;
        }
        try {
            worldGuardClass = Class.forName("com.sk89q.worldguard.WorldGuard");
            bukkitAdapterClass = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            regionContainerClass = Class.forName("com.sk89q.worldguard.protection.regions.RegionContainer");
            regionQueryClass = Class.forName("com.sk89q.worldguard.protection.regions.RegionQuery");
            applicableRegionSetClass =
                    Class.forName("com.sk89q.worldguard.protection.ApplicableRegionSet");
            protectedRegionClass = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedRegion");

            worldGuardInstance = worldGuardClass.getMethod("getInstance").invoke(null);
            available = true;
            logger.info("已成功挂钩 WorldGuard " + plugin.getDescription().getVersion() + "。");
            return true;
        } catch (Throwable t) {
            logger.log(Level.WARNING, "挂钩 WorldGuard 失败，撤离点功能将不可用。", t);
            available = false;
            return false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * 查询一个位置所在的全部 WorldGuard 区域 id（忽略大小写）。
     *
     * @return 区域 id 列表；WorldGuard 不可用或查询失败时返回空列表
     */
    public List<String> getRegionIds(Location location) {
        List<String> result = new ArrayList<>();
        if (!available || location == null || location.getWorld() == null) return result;
        try {
            World world = location.getWorld();

            // WorldGuardPlatform platform = WorldGuard.getInstance().getPlatform();
            Object platform = worldGuardClass.getMethod("getPlatform").invoke(worldGuardInstance);
            // RegionContainer container = platform.getRegionContainer();
            Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
            // World weWorld = BukkitAdapter.adapt(world);
            Object weWorld = bukkitAdapterClass.getMethod("adapt", World.class).invoke(null, world);
            // RegionManager manager = container.get(weWorld);
            Object manager = regionContainerClass.getMethod("get", Class.forName("com.sk89q.worldedit.world.World"))
                    .invoke(container, weWorld);
            if (manager == null) return result;

            // Location weLocation = BukkitAdapter.adapt(location);
            Object weLocation = bukkitAdapterClass.getMethod("adapt", Location.class).invoke(null, location);

            // RegionQuery query = container.createQuery();
            Object query = regionContainerClass.getMethod("createQuery").invoke(container);
            // ApplicableRegionSet set = query.getApplicableRegions(weLocation);
            Object set = regionQueryClass.getMethod("getApplicableRegions",
                            Class.forName("com.sk89q.worldedit.util.Location"))
                    .invoke(query, weLocation);

            for (Object region : (Iterable<?>) set) {
                String id = (String) protectedRegionClass.getMethod("getId").invoke(region);
                if (id != null) result.add(id.toLowerCase());
            }
        } catch (Throwable t) {
            // 查询失败不应该刷屏，只记一次调试日志
            logger.log(Level.FINE, "查询 WorldGuard 区域失败: " + t.getMessage());
        }
        return result;
    }

    /**
     * 判断玩家当前是否位于字符串指定的区域 id 内。
     */
    public boolean isInRegion(Location location, String regionId) {
        if (regionId == null) return false;
        return getRegionIds(location).contains(regionId.toLowerCase());
    }

    /**
     * 在指定世界查找一个区域是否存在。
     */
    public boolean regionExists(String worldName, String regionId) {
        if (!available || regionId == null) return false;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return false;
        try {
            Object platform = worldGuardClass.getMethod("getPlatform").invoke(worldGuardInstance);
            Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
            Object weWorld = bukkitAdapterClass.getMethod("adapt", World.class).invoke(null, world);
            Object manager = regionContainerClass.getMethod("get", Class.forName("com.sk89q.worldedit.world.World"))
                    .invoke(container, weWorld);
            if (manager == null) return false;
            Object region = manager.getClass().getMethod("getRegion", String.class)
                    .invoke(manager, regionId);
            return region != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 计算指定世界内某个区域的空间中心点。
     *
     * <p>用于在未显式配置警报锚点时，自动以「闸门所在区域」的中心作为通知圆心，
     * 避免所有通知都以 (0,64,0) 为中心造成的偏差。
     *
     * @return 区域中心点；WorldGuard 不可用或区域不存在时返回 null
     */
    public Location getRegionCenter(String worldName, String regionId) {
        if (!available || regionId == null) return null;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        try {
            Object platform = worldGuardClass.getMethod("getPlatform").invoke(worldGuardInstance);
            Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
            Object weWorld = bukkitAdapterClass.getMethod("adapt", World.class).invoke(null, world);
            Object manager = regionContainerClass.getMethod("get", Class.forName("com.sk89q.worldedit.world.World"))
                    .invoke(container, weWorld);
            if (manager == null) return null;

            Object region = manager.getClass().getMethod("getRegion", String.class)
                    .invoke(manager, regionId);
            if (region == null) return null;

            Object min = protectedRegionClass.getMethod("getMinimumPoint").invoke(region);
            Object max = protectedRegionClass.getMethod("getMaximumPoint").invoke(region);
            Class<?> bv3 = Class.forName("com.sk89q.worldedit.math.BlockVector3");
            double minX = (int) bv3.getMethod("x").invoke(min);
            double minY = (int) bv3.getMethod("y").invoke(min);
            double minZ = (int) bv3.getMethod("z").invoke(min);
            double maxX = (int) bv3.getMethod("x").invoke(max);
            double maxY = (int) bv3.getMethod("y").invoke(max);
            double maxZ = (int) bv3.getMethod("z").invoke(max);

            return new Location(world,
                    (minX + maxX) / 2.0 + 0.5,
                    (minY + maxY) / 2.0 + 0.5,
                    (minZ + maxZ) / 2.0 + 0.5);
        } catch (Throwable t) {
            logger.log(Level.FINE, "计算区域中心失败: " + t.getMessage());
            return null;
        }
    }

    /**
     * 计算一个撤离点对应的「玩家进入触发」判定。
     *
     * @return 匹配到的撤离点，未匹配返回 null
     */
    public ExtractPoint matchCheapest(Location location, Iterable<ExtractPoint> points) {
        List<String> ids = getRegionIds(location);
        if (ids.isEmpty()) return null;
        for (ExtractPoint p : points) {
            if (ids.contains(p.getRegion().toLowerCase())) return p;
        }
        return null;
    }

    /**
     * 便捷方法：玩家是否在撤离点区域内。
     */
    public boolean isPlayerIn(Player player, ExtractPoint point) {
        return isInRegion(player.getLocation(), point.getRegion());
    }
}
