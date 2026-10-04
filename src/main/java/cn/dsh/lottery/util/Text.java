package cn.dsh.lottery.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本处理工具：统一使用 MiniMessage 渲染，并兼容旧版 {@code &a} 颜色代码。
 */
public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer LEGACY_SERIALIZER =
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();
    private static final Pattern LEGACY_CODE = Pattern.compile("(?i)&([0-9a-fk-or])");
    private static final char SECTION = '\u00A7';

    private Text() {
    }

    /** 将旧版颜色符号转换为 MiniMessage 标签，已是 MiniMessage 的文本不受影响。 */
    public static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String out = raw.replace(SECTION, '&');
        Matcher matcher = LEGACY_CODE.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, "<" + tag(matcher.group(1).toLowerCase()) + ">");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public static Component mm(String raw) {
        return MM.deserialize(normalize(raw));
    }

    /** 带占位符替换的渲染；{@code ctx} 为 null 时按无占位符处理，绝不抛空指针。 */
    public static Component mm(String raw, PlaceholderContext ctx) {
        return mm(PlaceholderContext.applyTo(ctx, raw));
    }

    /** 批量渲染；{@code ctx} 为 null 或列表为 null 时都能安全返回。 */
    public static List<Component> mmList(List<String> raw, PlaceholderContext ctx) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream().map(line -> mm(line, ctx)).toList();
    }

    /** 去除所有格式标签，用于需要纯文本的场景（如对话框输入框的初始值）。 */
    public static String plain(String raw) {
        return MM.stripTags(normalize(raw));
    }

    /**
     * 把 MiniMessage 文本转换为旧版 {@code §} 颜色代码。
     * <p>
     * 用于需要「保留颜色但必须是普通字符串」的场景，例如流水记录：记录会被再次用
     * MiniMessage 渲染，若直接存 MiniMessage 标签，重新渲染会因文本已被转义而出现
     * 重复标签或颜色丢失。
     * <p>
     * 实现方式：先解析成组件树，再交给 Adventure 的 Legacy 序列化器输出 {@code §} 代码，
     * 因此颜色名（green → §a）、嵌套样式与 reset 都能正确处理。
     */
    public static String legacy(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        try {
            Component component = MM.deserialize(normalize(raw));
            return LEGACY_SERIALIZER.serialize(component);
        } catch (Throwable t) {
            // 解析失败时退回纯文本，绝不把字面标签写进持久化数据
            return plain(raw);
        }
    }

    /** 把多段 MiniMessage 文本转换为 {@code §} 颜色代码并拼接。 */
    public static String legacyJoin(java.util.List<String> parts, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(separator);
            }
            sb.append(parts.get(i));
        }
        return legacy(sb.toString());
    }

    /**
     * 发送消息给任意接收者：玩家使用 MiniMessage 渲染，控制台等转换为 {@code §} 颜色代码。
     */
    public static void sendAny(org.bukkit.command.CommandSender target, String miniMessage) {
        if (target == null) {
            return;
        }
        if (target instanceof org.bukkit.entity.Player) {
            send((net.kyori.adventure.audience.Audience) target, miniMessage);
        } else {
            target.sendMessage(legacy(miniMessage));
        }
    }

    /**
     * 向接收者发送组件消息。
     * <p>
     * {@code CommandSender} 上同时存在 {@code sendMessage(String)} 与
     * {@code sendMessage(Component)} 两套重载（后者来自 BungeeCord 兼容层），
     * 直接传 Component 在缺少 {@code net.md_5.bungee.api.chat} 依赖时无法通过编译。
     * 统一走 Adventure 的 {@link net.kyori.adventure.audience.Audience} 接口更稳定。
     */
    public static void send(net.kyori.adventure.audience.Audience target, String miniMessage) {
        if (target == null) {
            return;
        }
        target.sendMessage(mm(miniMessage));
    }

    /** 发送已构建好的组件。 */
    public static void send(net.kyori.adventure.audience.Audience target, Component component) {
        if (target == null || component == null) {
            return;
        }
        target.sendMessage(component);
    }

    private static String tag(String legacy) {
        return switch (legacy) {
            case "0" -> "black";
            case "1" -> "dark_blue";
            case "2" -> "dark_green";
            case "3" -> "dark_aqua";
            case "4" -> "dark_red";
            case "5" -> "dark_purple";
            case "6" -> "gold";
            case "7" -> "gray";
            case "8" -> "dark_gray";
            case "9" -> "blue";
            case "a" -> "green";
            case "b" -> "aqua";
            case "c" -> "red";
            case "d" -> "light_purple";
            case "e" -> "yellow";
            case "f" -> "white";
            case "k" -> "obfuscated";
            case "l" -> "bold";
            case "m" -> "strikethrough";
            case "n" -> "underlined";
            case "o" -> "italic";
            case "r" -> "reset";
            default -> legacy;
        };
    }
}
