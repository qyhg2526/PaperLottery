package cn.dsh.lottery.config;

import cn.dsh.lottery.util.PapiHook;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 条件集合：奖品、卡池共用的可见 / 可用性判定。
 * <p>
 * 支持的条件（全部为“与”关系）：
 * <ul>
 *     <li>{@code permission} —— 必须拥有的权限，可写多个；</li>
 *     <li>{@code permission-any} —— 拥有其中任意一个即可；</li>
 *     <li>{@code permission-multiplier} —— 权限权重倍率，格式 {@code 权限:倍率}；</li>
 *     <li>{@code placeholder} —— PlaceholderAPI 变量等值判定，格式 {@code %变量%:值}；</li>
 *     <li>{@code placeholder-min} —— PlaceholderAPI 变量数值下限，格式 {@code %变量%:数值}；</li>
 *     <li>{@code worlds} —— 仅在这些世界内可用。</li>
 * </ul>
 */
public record ConditionSet(
        List<String> permissions,
        List<String> permissionAny,
        List<PermissionMultiplier> permissionsMultiplier,
        List<String> placeholders,
        List<String> placeholderMin,
        List<String> worlds
) {

    private static final ConditionSet EMPTY =
            new ConditionSet(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

    public static ConditionSet empty() {
        return EMPTY;
    }

    /** 权限权重倍率条目。 */
    public record PermissionMultiplier(String permission, double multiplier) {
    }

    public static ConditionSet load(ConfigurationSection section) {
        if (section == null) {
            return EMPTY;
        }
        List<PermissionMultiplier> multipliers = new ArrayList<>();
        for (String raw : section.getStringList("permission-multiplier")) {
            String[] parts = raw.split(":", 2);
            if (parts.length != 2) {
                continue;
            }
            try {
                multipliers.add(new PermissionMultiplier(parts[0].trim(), Double.parseDouble(parts[1].trim())));
            } catch (NumberFormatException ignored) {
                // 单条写错不影响其它条件。
            }
        }
        return new ConditionSet(
                lower(section.getStringList("permission")),
                lower(section.getStringList("permission-any")),
                List.copyOf(multipliers),
                List.copyOf(section.getStringList("placeholder")),
                List.copyOf(section.getStringList("placeholder-min")),
                lower(section.getStringList("worlds"))
        );
    }

    public boolean isEmpty() {
        return permissions.isEmpty() && permissionAny.isEmpty() && permissionsMultiplier.isEmpty()
                && placeholders.isEmpty() && placeholderMin.isEmpty() && worlds.isEmpty();
    }

    /** 综合判定玩家是否满足全部条件。 */
    public boolean test(Player player) {
        if (isEmpty()) {
            return true;
        }
        if (player == null) {
            return false;
        }
        for (String permission : permissions) {
            if (!player.hasPermission(permission)) {
                return false;
            }
        }
        if (!permissionAny.isEmpty() && permissionAny.stream().noneMatch(player::hasPermission)) {
            return false;
        }
        if (!worlds.isEmpty() && worlds.stream().noneMatch(w -> w.equalsIgnoreCase(player.getWorld().getName()))) {
            return false;
        }
        for (String rule : placeholders) {
            String[] parts = rule.split(":", 2);
            if (parts.length != 2) {
                continue;
            }
            String actual = PapiHook.string(player, parts[0].trim());
            if (actual == null || !actual.equalsIgnoreCase(parts[1].trim())) {
                return false;
            }
        }
        for (String rule : placeholderMin) {
            String[] parts = rule.split(":", 2);
            if (parts.length != 2) {
                continue;
            }
            Double actual = PapiHook.numeric(player, parts[0].trim());
            if (actual == null) {
                return false;
            }
            try {
                if (actual < Double.parseDouble(parts[1].trim())) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    /** 权限加权倍率（多个命中时取最大值）。 */
    public double weightMultiplier(Player player) {
        double best = 1.0D;
        if (player == null) {
            return best;
        }
        for (PermissionMultiplier entry : permissionsMultiplier) {
            if (player.hasPermission(entry.permission())) {
                best = Math.max(best, entry.multiplier());
            }
        }
        return best;
    }

    private static List<String> lower(List<String> input) {
        return input.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }
}
