package com.example.extraction.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * 文本 / 消息工具：统一处理颜色代码与占位符替换。
 */
public final class Text {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    private Text() {
    }

    /**
     * 把 &amp; / § 颜色代码字符串转成 Component。
     */
    public static Component color(String raw) {
        if (raw == null) return Component.empty();
        return LEGACY.deserialize(raw.replace('§', '&'));
    }

    /**
     * 把颜色代码转成纯字符串（用于 BossBar 之外需要 String 的场景）。
     */
    public static String colorize(String raw) {
        if (raw == null) return "";
        return raw.replace('&', '§').replace("§§", "§");
    }

    /**
     * 去掉颜色代码，得到纯文本。
     */
    public static String strip(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("(?i)[&§][0-9A-FK-ORX]", "");
    }

    /**
     * 输出一条带前缀的消息给命令发送者。
     */
    public static void send(CommandSender to, FileConfiguration config, String message,
                            Object... replacements) {
        if (message == null || message.isEmpty()) return;
        String prefix = config.getString("messages.prefix", "&8[&c撤离&8] &r");
        String msg = prefix + message;
        msg = replace(msg, replacements);
        to.sendMessage(color(msg));
    }

    /**
     * 输出一条原始消息（不加前缀）。
     */
    public static void sendRaw(CommandSender to, String message, Object... replacements) {
        if (message == null || message.isEmpty()) return;
        to.sendMessage(color(replace(message, replacements)));
    }

    /**
     * 占位符替换，参数成对出现 {key, value}。
     */
    public static String replace(String text, Object... pairs) {
        if (text == null) return "";
        String result = text;
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String key = "{" + pairs[i] + "}";
            String value = pairs[i + 1] == null ? "" : String.valueOf(pairs[i + 1]);
            result = result.replace(key, value);
        }
        return result;
    }

    /**
     * 秒数格式化为 mm:ss 或纯秒。
     */
    public static String formatTime(int seconds) {
        if (seconds < 0) seconds = 0;
        if (seconds < 60) return seconds + "s";
        int m = seconds / 60;
        int s = seconds % 60;
        return String.format("%d:%02d", m, s);
    }
}
