package cn.dsh.lottery.model;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * 一次抽奖（单抽或连抽）的结算结果。
 *
 * @param player     抽奖玩家
 * @param pool       卡池 ID
 * @param amount     本次抽奖次数
 * @param prizes     实际获得的奖品（去重后）
 * @param counts     奖品 ID -&gt; 获得次数
 * @param items      需要发放的物品（已合并堆叠）
 * @param currencyId 消耗的货币 ID
 * @param totalCost  消耗总量
 * @param lucky      是否触发“欧皇时刻”（单抽命中最高品质）
 * @param pityHit    本次是否触发了保底
 * @param dropped    背包已满而掉落在地上的物品数量
 */
public record DrawOutcome(
        Player player,
        String pool,
        int amount,
        List<Prize> prizes,
        Map<String, Integer> counts,
        List<org.bukkit.inventory.ItemStack> items,
        String currencyId,
        double totalCost,
        boolean lucky,
        boolean pityHit,
        int dropped
) {

    /** 是否什么都没抽到（例如卡池为空）。 */
    public boolean isEmpty() {
        return prizes.isEmpty();
    }

    /** 最高品质 ID（用于结算播报）。 */
    public String bestRarity() {
        String best = null;
        int bestRank = Integer.MIN_VALUE;
        for (Prize prize : prizes) {
            int rank = prize.rarityRank();
            if (rank > bestRank) {
                bestRank = rank;
                best = prize.rarity();
            }
        }
        return best == null ? "" : best;
    }

    /** 本次最高品质的奖品。 */
    public Prize bestPrize() {
        Prize best = null;
        for (Prize prize : prizes) {
            if (best == null || prize.rarityRank() > best.rarityRank()) {
                best = prize;
            }
        }
        return best;
    }
}
