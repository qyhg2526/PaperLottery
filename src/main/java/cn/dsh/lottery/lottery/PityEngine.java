package cn.dsh.lottery.lottery;

import cn.dsh.lottery.config.Rarity;
import cn.dsh.lottery.data.PlayerData;
import cn.dsh.lottery.model.Pool;
import cn.dsh.lottery.model.Prize;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 保底规则引擎（纯逻辑，不依赖服务端运行时，便于单独验证）。
 * <p>
 * <b>保底语义</b>：{@code pity} 规则表示「连续 {@code threshold} 次没有获得该品质或更高品质」，
 * 那么下一次抽取必须强制获得该品质或更高品质。
 * <p>
 * 这里有两条关键不变量：
 * <ol>
 *   <li><b>不超限</b>：计数永远不会超过阈值。达到阈值后计数冻结，
 *       直到命中该品质而清零。否则界面会出现「31/30」这类脏数据，
 *       玩家也会看到「超过保底却不出货」。</li>
 *   <li><b>高优先级优先</b>：多条规则同时就绪时，必须满足品质最高的那条，
 *       否则低品质规则会一直抢先触发，高品质保底永远无法兑现。</li>
 * </ol>
 */
public final class PityEngine {

    private PityEngine() {
    }

    /**
     * 判断某品质是否达到或超过目标品质。
     * <p>
     * 未知品质按「不满足」处理，避免配置写错时静默失效。
     */
    public static boolean atLeast(Prize prize, String rarity) {
        Rarity target = Rarity.get(rarity);
        return target != null && prize.rarityRank() >= target.rank();
    }

    /** 规则是否已就绪（连续未命中次数达到阈值）。 */
    public static boolean isReady(Pool.PityRule rule, int counter) {
        return counter >= Math.max(1, rule.threshold());
    }

    /** 计数上限：用于界面展示与计数增长封顶。 */
    public static int threshold(Pool.PityRule rule) {
        return Math.max(1, rule.threshold());
    }

    /** 读取某项保底的有效计数（已封顶，绝不会超过阈值）。 */
    public static int counterOf(Pool pool, PlayerData data, String rarity) {
        for (Pool.PityRule rule : pool.pityRules()) {
            if (rule.rarity().equals(rarity)) {
                return Math.min(data.counter(pool.id(), rarity), threshold(rule));
            }
        }
        return data.counter(pool.id(), rarity);
    }

    /**
     * 选出本次必须强制满足的规则：所有「已就绪」规则中品质最高的那条。
     *
     * @return 需要强制的规则；没有就绪规则时返回 {@code null}
     */
    public static Pool.PityRule selectReady(Pool pool, PlayerData data) {
        Pool.PityRule best = null;
        int bestRank = Integer.MIN_VALUE;
        for (Pool.PityRule rule : pool.pityRules()) {
            if (!isReady(rule, data.counter(pool.id(), rule.rarity()))) {
                continue;
            }
            Rarity rarity = Rarity.get(rule.rarity());
            int rank = rarity == null ? Integer.MIN_VALUE + 1 : rarity.rank();
            if (best == null || rank > bestRank) {
                best = rule;
                bestRank = rank;
            }
        }
        return best;
    }

    /**
     * 命中某个奖品后更新全部保底计数。
     * <p>
     * 只维护<b>配置了保底规则</b>的品质计数，不会再给其它品质造出无意义的计数
     * （这是旧实现里「界面计数与真实抽数对不上」的根因之一）。
     */
    public static void applyWin(Pool pool, PlayerData data, Prize prize) {
        for (Pool.PityRule rule : pool.pityRules()) {
            if (atLeast(prize, rule.rarity())) {
                // 命中了该品质或更高：按配置决定是否清零
                if (rule.resetOnHit()) {
                    data.setCounter(pool.id(), rule.rarity(), 0);
                }
                continue;
            }
            // 未命中：累加，但绝不越过阈值
            int current = data.counter(pool.id(), rule.rarity());
            int threshold = threshold(rule);
            if (current < threshold) {
                data.setCounter(pool.id(), rule.rarity(), current + 1);
            }
        }
    }

    /**
     * 就绪规则中实际可用的最高品质奖品。
     *
     * @param candidates 当前可抽的奖品
     * @return 可强制兑现的奖品（按品质从高到低排序）；为空表示该保底暂时无法兑现
     *         （例如该品质的奖品都已达到限额）
     */
    public static List<Prize> guaranteedCandidates(List<Prize> candidates, String rarity) {
        return candidates.stream()
                .filter(prize -> atLeast(prize, rarity))
                .sorted(Comparator.comparingInt(Prize::rarityRank).reversed())
                .toList();
    }

    /** 调试：把某卡池的保底状态渲染成一行文本。 */
    public static String describe(Pool pool, PlayerData data) {
        StringBuilder sb = new StringBuilder(pool.id()).append(" {");
        for (Pool.PityRule rule : pool.pityRules()) {
            int counter = data.counter(pool.id(), rule.rarity());
            sb.append(rule.rarity()).append('=')
                    .append(counter).append('/').append(threshold(rule))
                    .append(isReady(rule, counter) ? "(就绪)" : "")
                    .append(' ');
        }
        return sb.append('}').toString();
    }

    /** 品质 ID 规范化，避免大小写导致同一品质出现两个计数。 */
    public static String normalize(String rarity) {
        return rarity == null ? "" : rarity.toLowerCase(Locale.ROOT);
    }
}
