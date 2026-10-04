package cn.dsh.lottery.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * PlaceholderAPI 软依赖桥接。
 * <p>
 * 通过反射调用 PlaceholderAPI，因此插件在没有安装 PAPI 的服务器上也能正常加载；
 * 若未安装，所有变量相关的条件判定会返回“不满足”，提示语中的变量保持原样。
 */
public final class PapiHook {

    private static boolean checked;
    private static boolean available;
    private static Method setPlaceholders;
    private static Method setPlaceholdersRelational;

    private PapiHook() {
    }

    private static void init() {
        if (checked) {
            return;
        }
        checked = true;
        Plugin plugin = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        try {
            Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            setPlaceholders = api.getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class);
            available = true;
        } catch (Throwable ignored) {
            available = false;
        }
    }

    public static boolean available() {
        init();
        return available;
    }

    public static void invalidate() {
        checked = false;
        available = false;
        setPlaceholders = null;
    }

    /** 解析文本中的 PlaceholderAPI 变量。 */
    public static String apply(Player player, String text) {
        if (text == null || text.isEmpty() || player == null) {
            return text;
        }
        init();
        if (!available || !text.contains("%")) {
            return text;
        }
        try {
            Object result = setPlaceholders.invoke(null, player, text);
            return result == null ? text : result.toString();
        } catch (Throwable t) {
            return text;
        }
    }

    /** 解析单个变量（例如 {@code %vault_eco_balance%}）的数值。 */
    public static Double numeric(Player player, String placeholder) {
        if (!available() || player == null || placeholder == null) {
            return null;
        }
        String raw = apply(player, placeholder).replace(",", "").trim();
        if (raw.isEmpty() || raw.equals(placeholder)) {
            return null;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 解析单个变量的字符串值。 */
    public static String string(Player player, String placeholder) {
        if (!available() || player == null || placeholder == null) {
            return null;
        }
        String raw = apply(player, placeholder);
        return raw.equals(placeholder) ? null : raw.trim();
    }
}
