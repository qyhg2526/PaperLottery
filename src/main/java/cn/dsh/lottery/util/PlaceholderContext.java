package cn.dsh.lottery.util;

import cn.dsh.lottery.config.Messages;
import cn.dsh.lottery.model.Pool;
import cn.dsh.lottery.model.Prize;
import org.bukkit.OfflinePlayer;

import java.text.DecimalFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 轻量占位符上下文：集中管理所有提示语中可用的 {@code %key%} 变量。
 */
public final class PlaceholderContext {

    private static final DecimalFormat NUMBER = new DecimalFormat("#,##0.##");
    private static final DecimalFormat PERCENT = new DecimalFormat("0.###");

    private final Map<String, String> values = new LinkedHashMap<>();

    private PlaceholderContext() {
    }

    /**
     * 安全解析占位符：上下文为 {@code null} 时原样返回模板。
     * <p>
     * 动画、播报等路径上很容易出现「只需要渲染文本、但没有玩家上下文」的情况，
     * 集中在这里兜底可以避免一处疏忽就抛空指针。
     */
    public static String applyTo(PlaceholderContext ctx, String template) {
        if (template == null) {
            return "";
        }
        return ctx == null ? template : ctx.apply(template);
    }

    public static PlaceholderContext of() {
        return new PlaceholderContext();
    }

    public static PlaceholderContext of(OfflinePlayer player) {
        PlaceholderContext ctx = new PlaceholderContext();
        if (player != null) {
            ctx.raw("player", player.getName() == null ? "unknown" : player.getName());
        }
        return ctx;
    }

    public PlaceholderContext raw(String key, String value) {
        values.put(key, value);
        return this;
    }

    public PlaceholderContext num(String key, Object value) {
        values.put(key, number(value));
        return this;
    }

    public PlaceholderContext percent(String key, double ratio) {
        values.put(key, PERCENT.format(ratio * 100.0D));
        return this;
    }

    public PlaceholderContext pool(Pool pool) {
        if (pool != null) {
            raw("pool", pool.id());
            raw("pool_name", Text.plain(pool.displayName()));
        }
        return this;
    }

    public PlaceholderContext prize(Prize prize) {
        if (prize != null) {
            raw("prize", prize.id());
            raw("prize_name", Text.plain(prize.displayName()));
            raw("rarity", prize.rarity());
            raw("rarity_name", Messages.rarityName(prize.rarity()));
        }
        return this;
    }

    public PlaceholderContext messages(Messages messages) {
        raw("prefix", messages.prefixPlain());
        return this;
    }

    /** 用当前上下文替换 {@code %key%}，未定义的占位符原样保留以便排错。 */
    public String apply(String template) {
        if (template == null || template.isEmpty() || values.isEmpty()) {
            return template == null ? "" : template;
        }
        String out = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            out = out.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        return out;
    }

    public static String number(Object value) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                return String.valueOf((long) d);
            }
            return NUMBER.format(d);
        }
        return value == null ? "0" : String.valueOf(value);
    }
}
