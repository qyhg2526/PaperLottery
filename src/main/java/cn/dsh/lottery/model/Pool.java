package cn.dsh.lottery.model;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;

/**
 * 抽奖卡池定义。
 *
 * @param id            卡池 ID
 * @param displayName   展示名（MiniMessage）
 * @param description   卡池描述行（MiniMessage）
 * @param icon          图标材质
 * @param currencyId    抽奖消耗的货币 ID
 * @param costs         抽奖次数 -&gt; 单次价格（未命中时回退到 {@code default-cost}）
 * @param defaultCost   默认单次价格
 * @param amounts       可选的连抽次数（对话框下拉选项）
 * @param permission    使用该卡池所需权限，可为空
 * @param conditions    卡池级条件
 * @param bonusWeight   卡池级权重加成倍率
 * @param announcedRarities 触发全服播报的品质列表
 * @param guaranteeRarity 每次抽取至少保证的品质（可空）
 * @param tenPullRarity   连抽（≥2 次）时至少保证的品质（可空）
 * @param guaranteeCount  连抽中保底品质的最低数量
 * @param pityRules     保底规则
 * @param dailyDrawLimit 每名玩家每日抽奖次数上限（-1 或 0 表示不限）
 * @param prizes        奖品列表
 */
public record Pool(
        String id,
        String displayName,
        List<String> description,
        Material icon,
        String currencyId,
        Map<Integer, Double> costs,
        double defaultCost,
        List<Integer> amounts,
        String permission,
        cn.dsh.lottery.config.ConditionSet conditions,
        double bonusWeight,
        List<String> announcedRarities,
        String guaranteeRarity,
        String tenPullRarity,
        int guaranteeCount,
        List<PityRule> pityRules,
        int dailyDrawLimit,
        List<Prize> prizes
) {

    /**
     * 保底规则：连续 {@code threshold} 次未获得 {@code rarity} 及以上品质时，下一次强制获得。
     *
     * @param rarity    目标品质
     * @param threshold 触发所需的连续未命中次数
     * @param resetOnHit 命中更高品质时是否重置计数（默认 true）
     */
    public record PityRule(String rarity, int threshold, boolean resetOnHit) {
    }

    public Pool {
        description = description == null ? List.of() : List.copyOf(description);
        costs = costs == null ? Map.of() : Map.copyOf(costs);
        amounts = amounts == null || amounts.isEmpty() ? List.of(1) : List.copyOf(amounts);
        announcedRarities = announcedRarities == null ? List.of() : List.copyOf(announcedRarities);
        pityRules = pityRules == null ? List.of() : List.copyOf(pityRules);
        prizes = prizes == null ? List.of() : List.copyOf(prizes);
    }

    /** 单次价格。 */
    public double costPerDraw(int amount) {
        Double exact = costs.get(amount);
        if (exact != null) {
            return exact;
        }
        return defaultCost;
    }

    /** 本次抽奖的总价。 */
    public double totalCost(int amount) {
        return costPerDraw(amount) * Math.max(1, amount);
    }

    /** 本卡池是否设置了每日抽奖次数上限。 */
    public boolean hasDailyDrawLimit() {
        return dailyDrawLimit > 0;
    }

    /** 该卡池可用的连抽选项（只保留有奖品的合法值）。 */
    public List<Integer> availableAmounts() {
        return amounts.stream().filter(a -> a != null && a >= 1).distinct().sorted().toList();
    }

    public boolean hasPermission(org.bukkit.entity.Player player) {
        return permission == null || permission.isBlank() || player.hasPermission(permission);
    }

    public boolean isUsable(org.bukkit.entity.Player player) {
        return hasPermission(player) && conditions.test(player);
    }

    public Prize prize(String prizeId) {
        if (prizeId == null) {
            return null;
        }
        for (Prize prize : prizes) {
            if (prize.id().equalsIgnoreCase(prizeId)) {
                return prize;
            }
        }
        return null;
    }

    public boolean empty() {
        return prizes.isEmpty();
    }
}
