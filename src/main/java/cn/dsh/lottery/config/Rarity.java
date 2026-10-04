package cn.dsh.lottery.config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 品质（稀有度）注册表：由 config.yml 的 {@code rarities} 节点定义。
 * <p>
 * 品质顺序即“稀有度等级”，用于抽卡保底、播报与排序。
 */
public final class Rarity {

    private static final Map<String, Rarity> REGISTRY = new LinkedHashMap<>();

    private final String id;
    private final String displayName;
    private final int weight;
    private final int rank;
    private final String broadcast;

    private Rarity(String id, String displayName, int weight, int rank, String broadcast) {
        this.id = id;
        this.displayName = displayName;
        this.weight = weight;
        this.rank = rank;
        this.broadcast = broadcast;
    }

    public static void register(String id, String displayName, int weight, String broadcast) {
        String key = id.toLowerCase(Locale.ROOT);
        REGISTRY.put(key, new Rarity(key, displayName, weight, REGISTRY.size(), broadcast));
    }

    public static void clear() {
        REGISTRY.clear();
    }

    public static Rarity get(String id) {
        return id == null ? null : REGISTRY.get(id.toLowerCase(Locale.ROOT));
    }

    public static Map<String, Rarity> all() {
        return REGISTRY;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** 该品质的权重（越大越常见），用于“抽卡保底”计算。 */
    public int weight() {
        return weight;
    }

    public int rank() {
        return rank;
    }

    /** 获得该品质奖品时的全服播报模板，为空表示不播报。 */
    public String broadcast() {
        return broadcast;
    }

    public boolean atLeast(String other) {
        Rarity target = get(other);
        return target != null && rank >= target.rank;
    }

    /** 品质的 MiniMessage 颜色，用于自动着色。 */
    public String color() {
        return switch (id) {
            case "common" -> "<gray>";
            case "uncommon" -> "<green>";
            case "rare" -> "<blue>";
            case "epic" -> "<light_purple>";
            case "legendary" -> "<gold>";
            case "mythic" -> "<red>";
            default -> "<white>";
        };
    }
}
