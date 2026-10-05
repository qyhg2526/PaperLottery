package cn.dsh.lottery.config;

import io.papermc.paper.registry.data.dialog.DialogBase;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 全局设置（config.yml 的 {@code settings} 节点）。
 *
 * @param debug                     调试日志
 * @param defaultPool               默认打开的卡池 ID
 * @param maxAmount                 单次最大连抽次数
 * @param defaultAmount             对话框默认连抽次数
 * @param amountOptions             对话框下拉可选次数
 * @param requireConfirmation       是否在抽卡前二次确认
 * @param cooldownMillis            抽奖冷却（毫秒）
 * @param directDrawOnClick         点击图标是否直接抽奖
 * @param dropWhenFull              背包满时是否掉落在地上
 * @param broadcastEnabled          是否开启全服播报
 * @param broadcastToConsole        播报是否也发送到控制台
 * @param luckyBroadcastRarity      “一发入魂”播报品质
 * @param soundEnabled              是否播放音效
 * @param bonusPermissionMultipliers 权限权重加成
 * @param bonusWeekend              周末权重倍率
 * @param bonusHours                时段权重加成
 * @param historyLimit              每名玩家保留的流水条数
 * @param bodyWidth                 对话框正文宽度（1-1024）
 * @param useItemBody               是否使用原版 {@code item} 正文组件展示奖品
 * @param afterActionRaw            对话框动作完成后的行为：close / none / wait
 * @param animation                 抽奖动画设置
 * @param dailyResetZoneRaw         「每日」重置所用时区：local 或 UTC 等 ZoneId
 */
public record Settings(
        boolean debug,
        String defaultPool,
        int maxAmount,
        int defaultAmount,
        List<Integer> amountOptions,
        boolean requireConfirmation,
        long cooldownMillis,
        boolean directDrawOnClick,
        boolean dropWhenFull,
        boolean broadcastEnabled,
        boolean broadcastToConsole,
        String luckyBroadcastRarity,
        boolean soundEnabled,
        List<ConditionSet.PermissionMultiplier> bonusPermissionMultipliers,
        double bonusWeekend,
        List<TimeBonus> bonusHours,
        int historyLimit,
        int bodyWidth,
        boolean useItemBody,
        String afterActionRaw,
        AnimationSettings animation,
        String dailyResetZoneRaw
) {

    /** 时段权重加成：{@code HH:mm-HH:mm:倍率}。 */
    public record TimeBonus(int startMinute, int endMinute, double multiplier) {

        public boolean matches(int minuteOfDay) {
            if (startMinute <= endMinute) {
                return minuteOfDay >= startMinute && minuteOfDay <= endMinute;
            }
            // 跨零点时段，例如 22:00-02:00
            return minuteOfDay >= startMinute || minuteOfDay <= endMinute;
        }
    }

    private static final java.util.regex.Pattern HOUR_BONUS =
            java.util.regex.Pattern.compile("^(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})\\s*:\\s*([0-9.]+)$");

    public static Settings load(FileConfiguration config, String defaultPool) {
        ConfigurationSection s = config.getConfigurationSection("settings");
        if (s == null) {
            return defaults(defaultPool);
        }
        List<Integer> amounts = s.getIntegerList("amount-options");
        if (amounts.isEmpty()) {
            amounts = List.of(1, 5, 10);
        }
        List<ConditionSet.PermissionMultiplier> permBonus = new ArrayList<>();
        for (String raw : s.getStringList("bonus.permission-multiplier")) {
            String[] parts = raw.split(":", 2);
            if (parts.length != 2) {
                continue;
            }
            try {
                permBonus.add(new ConditionSet.PermissionMultiplier(parts[0].trim(), Double.parseDouble(parts[1].trim())));
            } catch (NumberFormatException ignored) {
                // 忽略非法条目
            }
        }
        List<TimeBonus> hourBonus = new ArrayList<>();
        for (String raw : s.getStringList("bonus.hours")) {
            // 格式：HH:mm-HH:mm:倍率，例如 "20:00-23:00:1.5"
            java.util.regex.Matcher matcher = HOUR_BONUS.matcher(raw.trim());
            if (!matcher.matches()) {
                continue;
            }
            try {
                hourBonus.add(new TimeBonus(
                        parseMinute(matcher.group(1), matcher.group(2)),
                        parseMinute(matcher.group(3), matcher.group(4)),
                        Double.parseDouble(matcher.group(5))));
            } catch (NumberFormatException ignored) {
                // 忽略非法条目
            }
        }
        int bodyWidth = Math.max(1, Math.min(1024, s.getInt("dialog.body-width", 220)));
        return new Settings(
                s.getBoolean("debug", false),
                s.getString("default-pool", defaultPool),
                Math.max(1, s.getInt("max-amount", 100)),
                Math.max(1, s.getInt("default-amount", 1)),
                amounts.stream().filter(a -> a != null && a > 0).distinct().sorted().toList(),
                s.getBoolean("require-confirmation", true),
                Math.max(0L, s.getLong("cooldown-millis", 1500L)),
                s.getBoolean("direct-draw-on-click", false),
                s.getBoolean("drop-when-full", true),
                s.getBoolean("broadcast.enabled", true),
                s.getBoolean("broadcast.console", true),
                s.getString("broadcast.lucky-rarity", "legendary"),
                s.getBoolean("sounds.enabled", true),
                List.copyOf(permBonus),
                s.getDouble("bonus.weekend", 1.0D),
                List.copyOf(hourBonus),
                Math.max(0, s.getInt("history-limit", 50)),
                bodyWidth,
                s.getBoolean("dialog.use-item-body", false),
                s.getString("dialog.after-action", "close"),
                AnimationSettings.load(config.getConfigurationSection("animation")),
                s.getString("daily-draw-reset-zone", "local")
        );
    }

    public static Settings defaults(String defaultPool) {
        return new Settings(false, defaultPool, 100, 1, List.of(1, 5, 10), true, 1500L, false, true,
                true, true, "legendary", true, List.of(), 1.0D, List.of(), 50, 220, false, "close",
                AnimationSettings.defaults(), "local");
    }

    private static int parseMinute(String hour, String minute) {
        return Math.floorMod(Integer.parseInt(hour.trim()), 24) * 60 + Math.floorMod(Integer.parseInt(minute.trim()), 60);
    }

    /** 解析为 Dialog API 的行为枚举。 */
    public DialogBase.DialogAfterAction afterAction() {
        return switch (afterActionRaw == null ? "close" : afterActionRaw.toLowerCase(Locale.ROOT)) {
            case "none" -> DialogBase.DialogAfterAction.NONE;
            case "wait", "wait-for-response" -> DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE;
            default -> DialogBase.DialogAfterAction.CLOSE;
        };
    }

    /** 已告警过的非法时区值，避免每次调用都刷屏（record 不能有实例字段）。 */
    private static final java.util.Set<String> WARNED_ZONES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 「每日」判定所用的时区。
     * <p>
     * {@code local} 表示跟随服务器系统时区；也可直接写 ZoneId，例如 {@code UTC}、
     * {@code Asia/Shanghai}。跨时区运营的服务器通常用 {@code UTC} 统一重置时间。
     * 配置写错时回退到系统时区并告警一次。
     */
    public java.time.ZoneId dailyResetZone() {
        String raw = dailyResetZoneRaw == null ? "local" : dailyResetZoneRaw.trim();
        if (raw.isEmpty() || "local".equalsIgnoreCase(raw) || "server".equalsIgnoreCase(raw)) {
            return java.time.ZoneId.systemDefault();
        }
        try {
            return java.time.ZoneId.of(raw);
        } catch (Throwable t) {
            if (WARNED_ZONES.add(raw)) {
                org.bukkit.Bukkit.getLogger().warning("[PaperLottery] 无法解析 daily-draw-reset-zone=\""
                        + raw + "\"，已回退到服务器系统时区。可用值示例：local / UTC / Asia/Shanghai");
            }
            return java.time.ZoneId.systemDefault();
        }
    }

    /** 每日重置时区的可读名称，用于界面与命令提示。 */
    public String dailyResetZoneName() {
        return dailyResetZone().getId();
    }

    /** 计算当前时刻适用的全局权重倍率（活动 / 周末 / 权限）。 */
    public double globalBonus(org.bukkit.entity.Player player) {
        double multiplier = 1.0D;
        for (ConditionSet.PermissionMultiplier entry : bonusPermissionMultipliers) {
            if (player.hasPermission(entry.permission())) {
                multiplier = Math.max(multiplier, entry.multiplier());
            }
        }
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
        java.time.DayOfWeek day = now.getDayOfWeek();
        if (bonusWeekend != 1.0D && (day == java.time.DayOfWeek.SATURDAY || day == java.time.DayOfWeek.SUNDAY)) {
            multiplier *= bonusWeekend;
        }
        int minuteOfDay = now.getHour() * 60 + now.getMinute();
        for (TimeBonus bonus : bonusHours) {
            if (bonus.matches(minuteOfDay)) {
                multiplier *= bonus.multiplier();
                break;
            }
        }
        return Math.max(0.0D, multiplier);
    }

    public String debugPrefix() {
        return "[PaperLottery] ";
    }
}
