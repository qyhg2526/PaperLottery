package cn.dsh.lottery.model;

import cn.dsh.lottery.config.ConditionSet;
import cn.dsh.lottery.config.Rarity;
import cn.dsh.lottery.util.ItemBuilder;
import cn.dsh.lottery.util.PlaceholderContext;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 奖品定义。
 *
 * @param id          奖品 ID（同一卡池内唯一）
 * @param displayName 展示名（MiniMessage）
 * @param rarity      品质 ID，对应 config.yml 的 rarities
 * @param weight      基础权重（越大越容易抽中）
 * @param conditions  抽中条件（权限 / 世界 / PAPI 变量）
 * @param item        物品奖励，可为 null
 * @param currency    货币奖励，可为 null
 * @param commands    命令奖励（以控制台身份执行，支持 %player% 占位符）
 * @param bonusWeight 额外的固定权重加成（用于“幸运值”类加成）
 * @param limit       全服累计上限，-1 表示不限
 * @param playerLimit 单名玩家累计上限，-1 表示不限
 * @param dailyLimit  单名玩家每日上限，-1 表示不限
 */
public record Prize(
        String id,
        String displayName,
        String rarity,
        double weight,
        ConditionSet conditions,
        ItemSpec item,
        CurrencyReward currency,
        List<String> commands,
        double bonusWeight,
        int limit,
        int playerLimit,
        int dailyLimit
) {

    private static final Random RANDOM = new Random();

    public Prize {
        rarity = (rarity == null || rarity.isBlank()) ? "common" : rarity.toLowerCase(java.util.Locale.ROOT);
        weight = Math.max(0.0001D, weight);
        displayName = (displayName == null || displayName.isBlank()) ? id : displayName;
        commands = commands == null ? List.of() : List.copyOf(commands);
        conditions = conditions == null ? ConditionSet.empty() : conditions;
    }

    /** 物品奖励定义。 */
    public record ItemSpec(
            Material material,
            int minAmount,
            int maxAmount,
            String name,
            List<String> lore,
            List<String> enchants,
            Integer modelData,
            boolean unbreakable,
            boolean glow,
            String skullOwner,
            String skullUuid,
            String skullTexture,
            String skullSignature
    ) {
    }

    /** 货币奖励定义。 */
    public record CurrencyReward(String currencyId, double min, double max) {
    }

    public Rarity rarityInfo() {
        Rarity rarityInfo = Rarity.get(rarity);
        return rarityInfo;
    }

    public int rarityRank() {
        Rarity info = rarityInfo();
        return info == null ? 0 : info.rank();
    }

    /** 带品质颜色的展示名。 */
    public String coloredName() {
        Rarity info = rarityInfo();
        return (info == null ? "<white>" : info.color()) + displayName;
    }

    /**
     * 计算实际抽取权重。
     *
     * @param player     抽奖玩家
     * @param poolBonus  卡池级加成倍率
     * @param globalBonus 全局加成倍率（活动 / 周末 / 权限）
     */
    public double effectiveWeight(Player player, double poolBonus, double globalBonus) {
        double value = weight + bonusWeight;
        value *= conditions.weightMultiplier(player);
        value *= Math.max(0.0D, poolBonus);
        value *= Math.max(0.0D, globalBonus);
        return Math.max(0.0D, value);
    }

    /** 是否满足抽取条件（权限 / 世界 / 变量）。 */
    public boolean test(Player player) {
        return conditions.test(player);
    }

    /**
     * 该奖品当前是否可被抽中。
     *
     * @param globalCounts 全服累计发放次数查询：奖品 ID -&gt; 次数
     * @param playerCounts 该玩家累计发放次数查询：奖品 ID -&gt; 次数
     * @param dailyCounts  该玩家今日发放次数查询：奖品 ID -&gt; 次数
     */
    public boolean available(java.util.function.Function<String, Integer> globalCounts,
                             java.util.function.Function<String, Integer> playerCounts,
                             java.util.function.Function<String, Integer> dailyCounts) {
        if (limit >= 0 && globalCounts.apply(id) >= limit) {
            return false;
        }
        if (playerLimit >= 0 && playerCounts.apply(id) >= playerLimit) {
            return false;
        }
        return dailyLimit < 0 || dailyCounts.apply(id) < dailyLimit;
    }

    /** 构建物品奖励；无物品奖励时返回 null。 */
    public ItemStack buildItem(PlaceholderContext ctx) {
        if (item == null || item.material() == null || item.material().isAir()) {
            return null;
        }
        int amount = ItemBuilder.randomAmount(item.minAmount(), item.maxAmount());
        ItemStack stack = ItemBuilder.build(
                item.material(),
                amount,
                item.name() == null || item.name().isBlank() ? coloredName() : item.name(),
                item.lore(),
                item.enchants(),
                item.modelData(),
                item.unbreakable(),
                item.glow(),
                ctx
        );
        // 玩家头颅需要额外写入所有者信息（名称 / UUID / 皮肤纹理）
        if (item.skullOwner() != null || item.skullUuid() != null || item.skullTexture() != null) {
            ItemBuilder.applySkullOwner(stack, item.skullOwner(), item.skullUuid(),
                    item.skullTexture(), item.skullSignature(), ctx);
        }
        return ItemBuilder.tag(stack, "prize", id);
    }

    /** 随机货币奖励数额。 */
    public double rollCurrency() {
        if (currency == null) {
            return 0.0D;
        }
        double min = Math.min(currency.min(), currency.max());
        double max = Math.max(currency.min(), currency.max());
        if (min == max) {
            return min;
        }
        return min + RANDOM.nextDouble() * (max - min);
    }

    /** 生成概率面板中的描述行。 */
    public List<String> describe(java.util.function.DoubleFunction<String> percentFormatter) {
        List<String> lines = new ArrayList<>();
        lines.add("<dark_gray>ID: <gray>" + id);
        lines.add("<dark_gray>权重: <white>" + PlaceholderContext.number(weight));
        if (limit >= 0) {
            lines.add("<dark_gray>全服上限: <white>" + limit);
        }
        if (playerLimit >= 0) {
            lines.add("<dark_gray>个人上限: <white>" + playerLimit);
        }
        if (dailyLimit >= 0) {
            lines.add("<dark_gray>每日上限: <white>" + dailyLimit);
        }
        if (!conditions.isEmpty()) {
            lines.add("<dark_gray>条件: <yellow>需要满足前置条件");
        }
        lines.add("<dark_gray>概率: <white>" + percentFormatter.apply(0.0D));
        return lines;
    }
}
