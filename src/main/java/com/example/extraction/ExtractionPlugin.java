package com.example.extraction;

import com.example.extraction.command.ExtractCommand;
import com.example.extraction.config.ConfigManager;
import com.example.extraction.listener.PlayerMoveListener;
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
    private PlayerMoveListener moveListener;

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

        // 6. 事件监听
        moveListener = new PlayerMoveListener(this);
        getServer().getPluginManager().registerEvents(moveListener, this);

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

    /**
     * 重载全部配置与运行时状态。
     */
    public void reloadAll() {
        extractionManager.stop();
        configManager.load();
        switchManager.load();
        extractionManager.start();
    }
}
