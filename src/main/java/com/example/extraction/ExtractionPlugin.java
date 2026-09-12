package com.example.extraction;

import com.example.extraction.command.ExtractCommand;
import com.example.extraction.config.ConfigManager;
import com.example.extraction.listener.PlayerMoveListener;
import com.example.extraction.manager.BindManager;
import com.example.extraction.manager.ExtractionManager;
import com.example.extraction.manager.SwitchManager;
import com.example.extraction.util.WorldGuardHook;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 搜打撤 · 撤离点插件主类。
 */
public class ExtractionPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private WorldGuardHook worldGuardHook;
    private ExtractionManager extractionManager;
    private SwitchManager switchManager;
    private BindManager bindManager;
    private PlayerMoveListener moveListener;

    /** 拉杆交互防抖：playerUUID -> 上次交互毫秒 */
    private final java.util.Map<java.util.UUID, Long> interactDebounce = new java.util.HashMap<>();

    /** 拉杆绑定模式：playerUUID -> 待绑定的 switchId */
    private final java.util.Map<java.util.UUID, String> bindPending = new java.util.HashMap<>();
    /** 绑定模式开始时间：playerUUID -> 毫秒（30 秒超时） */
    private final java.util.Map<java.util.UUID, Long> bindPendingSince = new java.util.HashMap<>();

    private static final long BIND_TIMEOUT_MILLIS = 30_000L;
    private static final long INTERACT_DEBOUNCE_MILLIS = 200L;

    @Override
    public void onEnable() {
        // 1. 生成默认配置
        saveDefaultConfig();

        // 2. 挂钩 WorldGuard（软依赖，失败不阻断启动）
        worldGuardHook = new WorldGuardHook(getLogger());
        worldGuardHook.hook();

        // 3. 配置
        configManager = new ConfigManager(this);
        configManager.load();

        // 4. 闸门状态
        switchManager = new SwitchManager(this);
        switchManager.load();

        // 5. 运行时管理器
        extractionManager = new ExtractionManager(this);
        extractionManager.start();

        bindManager = new BindManager(this);

        // 6. 事件监听
        moveListener = new PlayerMoveListener(this);
        getServer().getPluginManager().registerEvents(moveListener, this);
        getServer().getPluginManager().registerEvents(
                new com.example.extraction.listener.LeverListener(this), this);

        // 7. 命令
        PluginCommand command = getCommand("extract");
        if (command != null) {
            ExtractCommand executor = new ExtractCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        } else {
            getLogger().severe("无法注册 /extract 命令，请检查 plugin.yml。");
        }

        getLogger().info("搜打撤撤离点插件已启用。");
    }

    @Override
    public void onDisable() {
        if (extractionManager != null) extractionManager.stop();
        if (switchManager != null) switchManager.save();
        getLogger().info("搜打撤撤离点插件已卸载。");
    }

    // ------------------------------------------------------------------

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public WorldGuardHook getWorldGuardHook() {
        return worldGuardHook;
    }

    public ExtractionManager getExtractionManager() {
        return extractionManager;
    }

    public SwitchManager getSwitchManager() {
        return switchManager;
    }

    public PlayerMoveListener getMoveListener() {
        return moveListener;
    }

    public BindManager getBindManager() {
        return bindManager;
    }

    // ------------------------------------------------------------------
    //  拉杆交互防抖
    // ------------------------------------------------------------------

    /**
     * 认领一次拉杆交互：200ms 内的重复交互（主手/副手双触发）返回 false。
     */
    public boolean tryClaimInteract(org.bukkit.entity.Player player) {
        long now = System.currentTimeMillis();
        Long last = interactDebounce.get(player.getUniqueId());
        if (last != null && now - last < INTERACT_DEBOUNCE_MILLIS) {
            return false;
        }
        interactDebounce.put(player.getUniqueId(), now);
        return true;
    }

    // ------------------------------------------------------------------
    //  拉杆绑定模式
    // ------------------------------------------------------------------

    /**
     * 进入绑定模式：30 秒内右键拉杆即绑定到指定 switchId。
     */
    public void setBindPending(java.util.UUID uuid, String switchId) {
        bindPending.put(uuid, switchId);
        bindPendingSince.put(uuid, System.currentTimeMillis());
    }

    /**
     * 取出并结束绑定模式。超时或从未进入则返回 null。
     */
    public String consumeBindPending(java.util.UUID uuid) {
        String switchId = bindPending.get(uuid);
        if (switchId == null) return null;
        Long since = bindPendingSince.get(uuid);
        bindPending.remove(uuid);
        bindPendingSince.remove(uuid);
        if (since == null || System.currentTimeMillis() - since > BIND_TIMEOUT_MILLIS) {
            return null;
        }
        return switchId;
    }

    /**
     * 重载配置时清理绑定模式状态。
     */
    public void clearBindPending() {
        bindPending.clear();
        bindPendingSince.clear();
    }

    /**
     * 重载全部配置与运行时状态。
     */
    public void reloadAll() {
        clearBindPending();
        extractionManager.stop();
        configManager.load();
        switchManager.load();
        extractionManager.start();
    }
}
