package com.example.extraction.command;

import com.example.extraction.ExtractionPlugin;
import com.example.extraction.model.ExtractPoint;
import com.example.extraction.model.ExtractType;
import com.example.extraction.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * /extract 管理命令。
 */
public class ExtractCommand implements CommandExecutor, TabCompleter {

    private final ExtractionPlugin plugin;

    public ExtractCommand(ExtractionPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "help" -> sendHelp(sender, label);
            case "list" -> doList(sender);
            case "info" -> doInfo(sender, args);
            case "reload" -> doReload(sender);
            case "switch" -> doSwitch(sender, args);
            case "reset" -> doReset(sender, args);
            case "start" -> doStart(sender, args);
            case "stop" -> doStop(sender, args);
            case "force" -> doForce(sender, args);
            case "here" -> doHere(sender);
            case "bind" -> doBind(sender, args);
            case "unbind" -> doUnbind(sender, args);
            default -> sendHelp(sender, label);
        }
        return true;
    }

    // ==================================================================
    //  子命令
    // ==================================================================

    private void doList(CommandSender sender) {
        if (!check(sender, "extraction.command.list")) return;
        var all = plugin.getConfigManager().getAll();
        if (all.isEmpty()) {
            Text.send(sender, plugin.getConfig(), "&e当前没有配置任何撤离点。");
            return;
        }
        Text.sendRaw(sender, "&8&m--------&r &c撤离点列表 &7(" + all.size() + ") &8&m--------");
        for (ExtractPoint p : all) {
            String status = p.isEnabled() ? "&a启用" : "&c禁用";
            Text.sendRaw(sender, " &7- &f" + p.getId() + " &8| &b" + typeName(p.getType())
                    + " &8| 区域 &f" + p.getRegion() + " &8| " + status);
        }
        Text.sendRaw(sender, "&7使用 &f/" + "extract info <id> &7查看详情。");
    }

    private void doInfo(CommandSender sender, String[] args) {
        if (!check(sender, "extraction.command.info")) return;
        if (args.length < 2) {
            Text.send(sender, plugin.getConfig(), "&c用法：/extract info <id>");
            return;
        }
        ExtractPoint p = plugin.getConfigManager().get(args[1]);
        if (p == null) {
            Text.send(sender, plugin.getConfig(), "&c未找到撤离点：&f" + args[1]);
            return;
        }
        Text.sendRaw(sender, "&8&m--------&r &c撤离点 &f" + p.getId() + " &8&m--------");
        Text.sendRaw(sender, " &7类型： &f" + typeName(p.getType()));
        Text.sendRaw(sender, " &7区域： &f" + p.getRegion()
                + (p.getWorldName() == null ? "" : " &7(世界 " + p.getWorldName() + ")"));
        Text.sendRaw(sender, " &7目标： &f" + p.getDestination());
        if (p.getType() == ExtractType.SWITCH) {
            Text.sendRaw(sender, " &7闸门 ID： &f" + p.getSwitchId());
            Text.sendRaw(sender, " &7全局倒计时： &f" + p.getGlobalCountdown() + "s &7(冷却 "
                    + p.getSwitchCooldown() + "s)");
            Text.sendRaw(sender, " &7闸门状态： " + (plugin.getSwitchManager().isActive(p.getSwitchId())
                    ? "&a已拉闸" : "&c未拉闸"));
            int remain = plugin.getExtractionManager().getGlobalRemaining(p.getSwitchId());
            if (remain >= 0) {
                Text.sendRaw(sender, " &7剩余时间： &e" + Text.formatTime(remain));
            }
            Text.sendRaw(sender, " &7警报音效： &f" + p.getAlarmSound().getKey()
                    + " &7音量 " + p.getAlarmVolume() + " 音调 " + p.getAlarmPitch());
            Text.sendRaw(sender, " &7警报半径： &f" + radiusText(p) + " 格");
        } else {
            Text.sendRaw(sender, " &7独立倒计时： &f" + p.getCountdown() + "s");
        }
        if (p.hasItemCondition()) {
            Text.sendRaw(sender, " &7物品需求： &f" + p.getItems().stream()
                    .map(Object::toString).collect(Collectors.joining(", ")));
        }
        Text.sendRaw(sender, " &7离开中断： " + yesNo(p.isInterruptOnMoveOut())
                + " &7受伤中断： " + yesNo(p.isInterruptOnDamage())
                + " &7死亡中断： " + yesNo(p.isInterruptOnDeath()));
        Text.sendRaw(sender, " &7清空背包： " + yesNo(p.isClearInventory())
                + " &7丢包撤： " + yesNo(p.isLoseBackpack())
                + " &7状态： " + (p.isEnabled() ? "&a启用" : "&c禁用"));
    }

    private void doReload(CommandSender sender) {
        if (!check(sender, "extraction.command.reload")) return;
        plugin.reloadAll();
        Text.send(sender, plugin.getConfig(), "&a配置已重载，共 "
                + plugin.getConfigManager().getAll().size() + " 个撤离点。");
    }

    /**
     * /extract switch <id> [on|off|toggle]
     * 手动操作闸门。id 可以是 switchId 或撤离点 id。
     */
    private void doSwitch(CommandSender sender, String[] args) {
        if (!check(sender, "extraction.command.switch")) return;
        if (args.length < 2) {
            Text.send(sender, plugin.getConfig(), "&c用法：/extract switch <switchId> [on|off|toggle]");
            return;
        }
        ExtractPoint point = resolvePoint(args[1]);
        if (point == null || point.getType() != ExtractType.SWITCH) {
            Text.send(sender, plugin.getConfig(), "&c未找到拉闸类型撤离点：&f" + args[1]);
            return;
        }
        String switchId = point.getSwitchId();
        String action = args.length >= 3 ? args[2].toLowerCase() : "toggle";
        Player operator = sender instanceof Player p ? p : null;

        switch (action) {
            case "on", "pull", "true" -> {
                String err = plugin.getExtractionManager().pullSwitch(switchId, operator);
                if (err != null) {
                    Text.send(sender, plugin.getConfig(), plugin.getConfig().getString(err,
                            "&c操作失败。"), "point", point.getId());
                } else {
                    Text.send(sender, plugin.getConfig(), "&a已拉闸：&f" + switchId);
                }
            }
            case "off", "reset", "false" -> {
                plugin.getExtractionManager().stopGlobalTimer(switchId);
                plugin.getSwitchManager().setActive(switchId, false);
                Text.send(sender, plugin.getConfig(), "&a已复位闸门：&f" + switchId);
            }
            case "toggle" -> {
                if (plugin.getSwitchManager().isActive(switchId)) {
                    plugin.getExtractionManager().stopGlobalTimer(switchId);
                    plugin.getSwitchManager().setActive(switchId, false);
                    Text.send(sender, plugin.getConfig(), "&a已复位闸门：&f" + switchId);
                } else {
                    String err = plugin.getExtractionManager().pullSwitch(switchId, operator);
                    if (err != null) {
                        Text.send(sender, plugin.getConfig(), plugin.getConfig().getString(err,
                                "&c操作失败。"), "point", point.getId());
                    } else {
                        Text.send(sender, plugin.getConfig(), "&a已拉闸：&f" + switchId);
                    }
                }
            }
            default -> Text.send(sender, plugin.getConfig(),
                    "&c未知操作：&f" + action + " &7(可用 on/off/toggle)");
        }
    }

    /**
     * /extract bind <switchId>
     * 进入绑定模式：30 秒内右键世界中的拉杆，把该拉杆绑定到指定闸门。
     * 玩家右键绑定的拉杆即触发拉闸，反馈只发给触发者本人。
     */
    private void doBind(CommandSender sender, String[] args) {
        if (!check(sender, "extraction.command.switch")) return;
        if (!(sender instanceof Player player)) {
            Text.send(sender, plugin.getConfig(), "&c该命令只能由玩家执行。");
            return;
        }
        if (args.length < 2) {
            Text.send(sender, plugin.getConfig(), "&c用法：/extract bind <switchId>");
            return;
        }
        String switchId = args[1];
        boolean found = plugin.getConfigManager().getBySwitch(switchId).stream()
                .anyMatch(p -> p.getType() == ExtractType.SWITCH);
        if (!found) {
            Text.send(sender, plugin.getConfig(), "&c未找到拉闸类型撤离点：&f" + switchId);
            return;
        }
        plugin.setBindPending(player.getUniqueId(), switchId);
        Text.send(sender, plugin.getConfig(), "&a绑定模式已开启（30 秒）。");
        Text.send(sender, plugin.getConfig(), "&7请右键点击要绑定的 &f拉杆&7，坐标将写入配置。");
    }

    /**
     * /extract unbind <switchId>
     * 清空某闸门的全部拉杆绑定。
     */
    private void doUnbind(CommandSender sender, String[] args) {
        if (!check(sender, "extraction.command.switch")) return;
        if (args.length < 2) {
            Text.send(sender, plugin.getConfig(), "&c用法：/extract unbind <switchId>");
            return;
        }
        ExtractPoint point = resolvePoint(args[1]);
        if (point == null || point.getType() != ExtractType.SWITCH) {
            Text.send(sender, plugin.getConfig(), "&c未找到拉闸类型撤离点：&f" + args[1]);
            return;
        }
        plugin.getConfig().set("extraction-points." + point.getId() + ".switch.levers", null);
        plugin.saveConfig();
        plugin.getConfigManager().load();
        Text.send(sender, plugin.getConfig(), "&a已清空 &f" + point.getId() + " &a的全部拉杆绑定。");
    }

    /**
     * /extract reset <id|all>
     * 重置撤离点运行时状态（清空玩家计时、全局倒计时、闸门与冷却）。
     */
    private void doReset(CommandSender sender, String[] args) {
        if (!check(sender, "extraction.command.reset")) return;
        if (args.length < 2) {
            Text.send(sender, plugin.getConfig(), "&c用法：/extract reset <id|all>");
            return;
        }
        String target = args[1];
        if (target.equalsIgnoreCase("all")) {
            // 简单粗暴：重建运行时状态
            plugin.getExtractionManager().stop();
            plugin.getExtractionManager().start();
            for (String sw : plugin.getSwitchManager().snapshot().keySet()) {
                plugin.getSwitchManager().setActive(sw, false);
                plugin.getSwitchManager().clearCooldown(sw);
            }
            plugin.getSwitchManager().save();
            Text.send(sender, plugin.getConfig(), "&a已重置全部撤离点运行状态。");
            return;
        }
        ExtractPoint point = plugin.getConfigManager().get(target);
        if (point == null) {
            Text.send(sender, plugin.getConfig(), "&c未找到撤离点：&f" + target);
            return;
        }
        if (point.getType() == ExtractType.SWITCH) {
            plugin.getExtractionManager().stopGlobalTimer(point.getSwitchId());
            plugin.getSwitchManager().setActive(point.getSwitchId(), false);
            plugin.getSwitchManager().clearCooldown(point.getSwitchId());
            plugin.getSwitchManager().save();
        }
        Text.send(sender, plugin.getConfig(), "&a已重置撤离点 &f" + point.getId());
    }

    /**
     * /extract start <id>
     * 直接启动某撤离点的全局倒计时（不经过闸门状态）。
     */
    private void doStart(CommandSender sender, String[] args) {
        if (!check(sender, "extraction.command.force")) return;
        if (args.length < 2) {
            Text.send(sender, plugin.getConfig(), "&c用法：/extract start <id>");
            return;
        }
        ExtractPoint point = plugin.getConfigManager().get(args[1]);
        if (point == null) {
            Text.send(sender, plugin.getConfig(), "&c未找到撤离点：&f" + args[1]);
            return;
        }
        if (point.getType() != ExtractType.SWITCH) {
            Text.send(sender, plugin.getConfig(), "&c只有拉闸类型撤离点支持全局倒计时。");
            return;
        }
        plugin.getSwitchManager().setActive(point.getSwitchId(), true);
        plugin.getExtractionManager().startGlobalTimer(point);
        Text.send(sender, plugin.getConfig(), "&a已启动 &f" + point.getId() + " &a的全局倒计时（"
                + point.getGlobalCountdown() + "s）。");
    }

    /**
     * /extract stop <id>
     * 停止全局倒计时。
     */
    private void doStop(CommandSender sender, String[] args) {
        if (!check(sender, "extraction.command.force")) return;
        if (args.length < 2) {
            Text.send(sender, plugin.getConfig(), "&c用法：/extract stop <id>");
            return;
        }
        ExtractPoint point = plugin.getConfigManager().get(args[1]);
        if (point == null) {
            Text.send(sender, plugin.getConfig(), "&c未找到撤离点：&f" + args[1]);
            return;
        }
        boolean ok = plugin.getExtractionManager().stopGlobalTimer(point.getSwitchId());
        Text.send(sender, plugin.getConfig(), ok ? "&a已停止 &f" + point.getId() + " &a的倒计时。"
                : "&e该撤离点当前没有进行中的倒计时。");
    }

    /**
     * /extract force <player> <id>
     * 强制让某玩家立即撤离（跳过倒计时与物品条件）。
     */
    private void doForce(CommandSender sender, String[] args) {
        if (!check(sender, "extraction.command.force")) return;
        if (args.length < 3) {
            Text.send(sender, plugin.getConfig(), "&c用法：/extract force <玩家> <撤离点id>");
            return;
        }
        Player target = plugin.getServer().getPlayerExact(args[1]);
        if (target == null) {
            Text.send(sender, plugin.getConfig(), "&c玩家 &f" + args[1] + " &c不在线。");
            return;
        }
        ExtractPoint point = plugin.getConfigManager().get(args[2]);
        if (point == null) {
            Text.send(sender, plugin.getConfig(), "&c未找到撤离点：&f" + args[2]);
            return;
        }
        boolean ok = plugin.getExtractionManager().completeExtraction(target, point, true);
        Text.send(sender, plugin.getConfig(), ok ? "&a已强制 &f" + target.getName()
                + " &a从 &f" + point.getId() + " &a撤离。" : "&c撤离失败，请检查目标世界是否加载。");
    }

    /**
     * /extract here
     * 查看自己当前所在区域及匹配的撤离点。
     */
    private void doHere(CommandSender sender) {
        if (!check(sender, "extraction.command.here")) return;
        if (!(sender instanceof Player player)) {
            Text.send(sender, plugin.getConfig(), "&c该命令只能由玩家执行。");
            return;
        }
        var ids = plugin.getWorldGuardHook().getRegionIds(player.getLocation());
        Text.sendRaw(sender, "&7当前位置的区域： &f"
                + (ids.isEmpty() ? "无" : String.join(", ", ids)));

        // 直接在当前位置匹配配置中的撤离点
        ExtractPoint point = null;
        for (String regionId : ids) {
            ExtractPoint candidate = plugin.getConfigManager()
                    .getByRegion(regionId, player.getWorld().getName());
            if (candidate != null) {
                point = candidate;
                break;
            }
        }
        if (point != null) {
            Text.sendRaw(sender, "&7匹配的撤离点： &a" + point.getId() + " &7(" + typeName(point.getType()) + ")");
        } else {
            Text.sendRaw(sender, "&7匹配的撤离点： &c无");
        }
        int remain = plugin.getExtractionManager().getPlayerRemaining(player);
        if (remain >= 0) {
            Text.sendRaw(sender, "&7你的撤离倒计时： &e" + Text.formatTime(remain));
        }
        // 全局倒计时状态
        if (point != null && point.getType() == ExtractType.SWITCH) {
            int global = plugin.getExtractionManager().getGlobalRemaining(point.getSwitchId());
            Text.sendRaw(sender, "&7闸门状态： "
                    + (plugin.getSwitchManager().isActive(point.getSwitchId()) ? "&a已开启" : "&c未开启")
                    + (global >= 0 ? " &7剩余 &e" + Text.formatTime(global) : ""));
        }
    }

    // ==================================================================
    //  辅助
    // ==================================================================

    private ExtractPoint resolvePoint(String input) {
        ExtractPoint p = plugin.getConfigManager().get(input);
        if (p != null) return p;
        var list = plugin.getConfigManager().getBySwitch(input);
        return list.isEmpty() ? null : list.get(0);
    }

    private String radiusText(ExtractPoint p) {
        double r = p.getAlarmRadius();
        if (r <= 0) r = plugin.getConfig().getDouble("settings.notification.radius", 300.0);
        return String.valueOf((int) r);
    }

    private String yesNo(boolean b) {
        return b ? "&a是" : "&c否";
    }

    private String typeName(ExtractType type) {
        return switch (type) {
            case NORMAL -> "常规撤离";
            case SWITCH -> "拉闸撤离";
            case ITEM -> "物品撤离";
        };
    }

    private boolean check(CommandSender sender, String permission) {
        if (sender.hasPermission(permission) || sender.hasPermission("extraction.admin")) return true;
        Text.send(sender, plugin.getConfig(), plugin.getConfig()
                .getString("messages.no-permission", "&c你没有权限执行该命令。"));
        return false;
    }

    private void sendHelp(CommandSender sender, String label) {
        Text.sendRaw(sender, "&8&m--------&r &c撤离点插件帮助 &8&m--------");
        Text.sendRaw(sender, " &f/" + label + " list &7- 列出全部撤离点");
        Text.sendRaw(sender, " &f/" + label + " info <id> &7- 查看撤离点详情");
        Text.sendRaw(sender, " &f/" + label + " here &7- 查看当前所在撤离点");
        Text.sendRaw(sender, " &f/" + label + " switch <id> [on|off|toggle] &7- 操作闸门");
        Text.sendRaw(sender, " &f/" + label + " bind <switchId> &7- 绑定拉杆（右键拉杆拉闸）");
        Text.sendRaw(sender, " &f/" + label + " unbind <switchId> &7- 清空拉杆绑定");
        Text.sendRaw(sender, " &f/" + label + " start <id> &7- 直接启动全局倒计时");
        Text.sendRaw(sender, " &f/" + label + " stop <id> &7- 停止全局倒计时");
        Text.sendRaw(sender, " &f/" + label + " reset <id|all> &7- 重置运行状态");
        Text.sendRaw(sender, " &f/" + label + " force <玩家> <id> &7- 强制撤离某玩家");
        Text.sendRaw(sender, " &f/" + label + " reload &7- 重载配置");
    }

    // ==================================================================
    //  补全
    // ==================================================================

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            result.addAll(Arrays.asList("list", "info", "here", "switch", "bind", "unbind",
                    "start", "stop", "reset", "force", "reload", "help"));
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("info") || sub.equals("start") || sub.equals("stop")) {
                plugin.getConfigManager().getAll().forEach(p -> result.add(p.getId()));
            } else if (sub.equals("switch")) {
                plugin.getConfigManager().getAll().stream()
                        .filter(p -> p.getType() == ExtractType.SWITCH)
                        .forEach(p -> result.add(p.getSwitchId()));
            } else if (sub.equals("reset")) {
                result.add("all");
                plugin.getConfigManager().getAll().forEach(p -> result.add(p.getId()));
            } else if (sub.equals("force")) {
                plugin.getServer().getOnlinePlayers().forEach(p -> result.add(p.getName()));
            }
        } else if (args.length == 3) {
            String sub = args[0].toLowerCase();
            if (sub.equals("force")) {
                plugin.getConfigManager().getAll().forEach(p -> result.add(p.getId()));
            } else if (sub.equals("switch")) {
                result.addAll(Arrays.asList("on", "off", "toggle"));
            }
        }

        String last = args[args.length - 1].toLowerCase();
        return result.stream()
                .filter(s -> s.toLowerCase().startsWith(last))
                .sorted()
                .collect(Collectors.toList());
    }

    /** 预留：占位，避免未使用告警。 */
    @SuppressWarnings("unused")
    private Map<String, String> unusedPlaceholder = new HashMap<>();
}
