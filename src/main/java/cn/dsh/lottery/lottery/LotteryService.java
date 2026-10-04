package cn.dsh.lottery.lottery;

import cn.dsh.lottery.PaperLotteryPlugin;
import cn.dsh.lottery.PluginContext;
import cn.dsh.lottery.config.Messages;
import cn.dsh.lottery.config.Rarity;
import cn.dsh.lottery.config.Settings;
import cn.dsh.lottery.currency.Currency;
import cn.dsh.lottery.data.PlayerData;
import cn.dsh.lottery.event.LotteryDrawEvent;
import cn.dsh.lottery.event.LotteryPreDrawEvent;
import cn.dsh.lottery.model.DrawOutcome;
import cn.dsh.lottery.model.Pool;
import cn.dsh.lottery.model.Prize;
import cn.dsh.lottery.model.PullRecord;
import cn.dsh.lottery.util.PlaceholderContext;
import cn.dsh.lottery.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 抽奖核心服务：负责校验、扣费、概率计算、保底判定、奖励发放与统计。
 * <p>
 * 概率模型：权重轮盘（weighted roulette）。
 * <pre>
 *   实际权重 = (基础权重 + 固定加成) × 权限倍率 × 卡池倍率 × 全局活动倍率
 *   命中概率 = 实际权重 / 所有可用奖品权重之和
 * </pre>
 * 达到 {@code limit} / {@code player-limit} / {@code daily-limit} 上限的奖品会从轮盘中移除，
 * 其余奖品权重自动重新归一化。
 */
public final class LotteryService {

    private final PaperLotteryPlugin plugin;
    private final PluginContext ctx;
    private final Random random = new Random();

    /** 正在结算中的玩家，防止连点造成重复扣款。 */
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    /** 已完成收尾的结果（对象身份去重，防止中断重入导致重复播报）。 */
    private final Set<DrawOutcome> completed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** 玩家冷却到期时间戳。 */
    private final Map<UUID, Long> cooldownUntil = new ConcurrentHashMap<>();
    /** 待二次确认的抽奖请求。 */
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    /** 抽奖动画引擎（可空）。 */
    private cn.dsh.lottery.animation.AnimationRunner animation;
    /** 保底无法兑现的告警限流（卡池|品质 -> 上次告警时间）。 */
    private final Map<String, Long> pityWarnedAt = new ConcurrentHashMap<>();

    /** 待确认的抽奖参数。 */
    public record Pending(String poolId, int amount, long createdAt) {
    }

    /** 单次抽取的中间结果。 */
    private record Rolled(Prize prize, Pool.PityRule forcedBy) {

        boolean pityForced() {
            return forcedBy != null;
        }
    }

    public LotteryService(PaperLotteryPlugin plugin, PluginContext ctx) {
        this.plugin = plugin;
        this.ctx = ctx;
    }

    // ------------------------------------------------------------------ 对外 API

    /**
     * 执行抽奖，必须在主线程调用。
     *
     * @param player 玩家
     * @param pool   卡池
     * @param amount 抽奖次数
     */
    public Outcome draw(Player player, Pool pool, int amount) {
        Settings settings = ctx.settings();
        if (pool == null) {
            return new Outcome.Failure(Messages.K.UNKNOWN_POOL, PlaceholderContext.of(player).raw("pool", "?"));
        }
        int draws = Math.max(1, Math.min(amount, settings.maxAmount()));
        PlaceholderContext baseCtx = PlaceholderContext.of(player).pool(pool);

        // 1) 前置校验
        if (!pool.hasPermission(player) || !pool.conditions().test(player)) {
            return new Outcome.Failure(Messages.K.POOL_LOCKED, baseCtx);
        }
        if (pool.empty()) {
            return new Outcome.Failure(Messages.K.POOL_EMPTY, baseCtx);
        }
        if (busy.contains(player.getUniqueId())) {
            return new Outcome.Failure(Messages.K.BUSY, baseCtx);
        }
        if (animation != null && animation.isAnimating(player)) {
            // 上一次抽奖的动画还没播完，避免叠加界面与重复扣费
            return new Outcome.Failure(Messages.K.BUSY, baseCtx);
        }
        long now = System.currentTimeMillis();
        long until = cooldownUntil.getOrDefault(player.getUniqueId(), 0L);
        if (until > now) {
            return new Outcome.Failure(Messages.K.COOLDOWN,
                    baseCtx.raw("time", String.format(Locale.ROOT, "%.1f", (until - now) / 1000.0D)));
        }

        Currency currency = ctx.currencies().get(pool.currencyId());
        if (currency == null || !currency.isAvailable()) {
            return new Outcome.Failure(Messages.K.CURRENCY_MISSING, baseCtx.raw("currency", pool.currencyId()));
        }
        double cost = pool.totalCost(draws);
        if (cost > 0 && !currency.has(player, cost)) {
            return new Outcome.Failure(Messages.K.INSUFFICIENT, costContext(baseCtx, currency, player, cost));
        }

        // 2) 允许其它插件介入（取消或修改次数）
        LotteryPreDrawEvent preEvent = new LotteryPreDrawEvent(player, pool, draws);
        Bukkit.getPluginManager().callEvent(preEvent);
        if (preEvent.isCancelled()) {
            return new Outcome.Failure(Messages.K.DRAW_FAILED, baseCtx);
        }
        draws = Math.max(1, Math.min(preEvent.getAmount(), settings.maxAmount()));
        cost = pool.totalCost(draws);
        if (cost > 0 && !currency.has(player, cost)) {
            return new Outcome.Failure(Messages.K.INSUFFICIENT, costContext(baseCtx, currency, player, cost));
        }

        // 3) 扣费 + 抽取（异常时原路退回）
        busy.add(player.getUniqueId());
        boolean charged = false;
        double chargedAmount = cost;
        try {
            if (chargedAmount > 0) {
                if (!currency.withdraw(player, chargedAmount)) {
                    return new Outcome.Failure(Messages.K.CHARGE_FAILED, costContext(baseCtx, currency, player, cost));
                }
                charged = true;
            }

            PlayerData data = ctx.data().get(player.getUniqueId(), player.getName());
            List<Prize> won = new ArrayList<>();
            Map<String, Integer> counts = new LinkedHashMap<>();
            boolean pityHit = false;
            double cheapestWeight = Double.MAX_VALUE;

            for (int i = 0; i < draws; i++) {
                Rolled rolled = roll(player, pool, data, draws);
                if (rolled == null || rolled.prize() == null) {
                    continue;
                }
                Prize prize = rolled.prize();
                pityHit |= rolled.pityForced();
                won.add(prize);
                counts.merge(prize.id(), 1, Integer::sum);
                cheapestWeight = Math.min(cheapestWeight,
                        prize.effectiveWeight(player, pool.bonusWeight(), settings.globalBonus(player)));
                // 统计与保底计数全部交给 registerWin 统一处理：
                // 这里绝不能按「奖品品质」再自增一次计数器，否则会给没有保底规则的品质
                // 造出幽灵计数，并让界面上的保底进度与真实抽数对不上。
                registerWin(pool, data, prize);
            }

            if (won.isEmpty()) {
                if (charged) {
                    currency.deposit(player, chargedAmount);
                }
                return new Outcome.Failure(Messages.K.DRAW_EMPTY, baseCtx);
            }

            // 4) 合并同类奖品后发放
            List<Prize> unique = new ArrayList<>();
            for (Prize prize : won) {
                if (unique.stream().noneMatch(p -> p.id().equals(prize.id()))) {
                    unique.add(prize);
                }
            }
            List<ItemStack> items = new ArrayList<>();
            int dropped = 0;
            for (Prize prize : unique) {
                int times = counts.getOrDefault(prize.id(), 1);
                for (int t = 0; t < times; t++) {
                    RewardApplier.Applied applied = RewardApplier.apply(
                            plugin, ctx.currencies(), player, pool, prize, settings.dropWhenFull());
                    dropped += applied.dropped();
                    ItemStack stack = prize.buildItem(PlaceholderContext.of(player).pool(pool).prize(prize));
                    if (stack != null) {
                        items.add(stack);
                    }
                }
            }

            Prize best = unique.getFirst();
            for (Prize prize : unique) {
                if (prize.rarityRank() > best.rarityRank()) {
                    best = prize;
                }
            }
            // “一发入魂”：单抽即命中轮盘中权重最低（最稀有）的奖品
            boolean lucky = draws == 1 && unique.size() == 1
                    && cheapestWeight <= minWeight(player, pool, data);

            data.addTotal(pool.id(), draws);
            data.addHistory(new PullRecord(System.currentTimeMillis(), pool.id(), draws, currency.id(), chargedAmount,
                    RewardApplier.summaryForStorage(unique, counts)), settings.historyLimit());
            ctx.data().markDirty();
            cooldownUntil.put(player.getUniqueId(), System.currentTimeMillis() + settings.cooldownMillis());

            DrawOutcome result = new DrawOutcome(player, pool.id(), draws, List.copyOf(unique), Map.copyOf(counts),
                    List.copyOf(items), currency.id(), chargedAmount, lucky, pityHit, dropped);

            // 注意：这里只做结算，不发送提示、不播报、不触发事件。
            // 调用方（UiManager）可以在播放完抽奖动画后再调用 complete(result, pool)，
            // 这样动画期间不会重复扣费或重复发奖。
            return new Outcome.Success(result);
        } catch (Throwable t) {
            if (charged) {
                try {
                    currency.deposit(player, chargedAmount);
                    plugin.getLogger().warning("抽奖异常，已退回 " + chargedAmount + " " + currency.id()
                            + " 给玩家 " + player.getName());
                } catch (Throwable ignored) {
                    plugin.getLogger().severe("退回抽奖消耗失败，请手动补偿玩家 " + player.getName());
                }
            }
            plugin.getLogger().severe("抽奖执行异常：" + t);
            for (StackTraceElement element : t.getStackTrace()) {
                plugin.getLogger().severe("    at " + element);
            }
            return new Outcome.Failure(Messages.K.DRAW_FAILED, baseCtx);
        } finally {
            busy.remove(player.getUniqueId());
        }
    }

    /**
     * 收尾：发送结算提示、全服播报并触发 {@link LotteryDrawEvent}。
     * <p>
     * 与 {@link #draw} 分离是为了让界面层先播放抽奖动画，再展示结果。
     * 重复调用是安全的（内部有去重标记），避免动画被中断时出现双重播报。
     */
    public void complete(DrawOutcome result) {
        if (result == null || !markCompleted(result)) {
            return;
        }
        Pool pool = ctx.config().pool(result.pool());
        Currency currency = ctx.currencies().get(result.currencyId());
        notifyResult(result, pool, currency, result.currencyId());
        Bukkit.getPluginManager().callEvent(new LotteryDrawEvent(result));
    }

    /** 以对象身份去重，保证同一次抽奖只会收尾一次。 */
    private boolean markCompleted(DrawOutcome result) {
        synchronized (completed) {
            return completed.add(result);
        }
    }

    /** 只计算结果，不扣费、不发放（用于展示“预览”与十连模拟）。 */
    public List<Prize> preview(Player player, Pool pool, int amount) {
        List<Prize> preview = new ArrayList<>();
        if (pool == null) {
            return preview;
        }
        PlayerData shadow = ctx.data().get(player.getUniqueId(), player.getName());
        for (int i = 0; i < Math.max(1, amount); i++) {
            Rolled rolled = roll(player, pool, shadow, amount);
            if (rolled != null && rolled.prize() != null) {
                preview.add(rolled.prize());
            }
        }
        return preview;
    }

    /** 奖品概率表：奖品 -&gt; 概率（0~1，不考虑个人条件与限额，用于公示面板）。 */
    public Map<Prize, Double> rates(Pool pool) {
        Map<Prize, Double> rates = new LinkedHashMap<>();
        double total = 0.0D;
        for (Prize prize : pool.prizes()) {
            double weight = prize.effectiveWeight(null, pool.bonusWeight(), 1.0D);
            rates.put(prize, weight);
            total += weight;
        }
        if (total <= 0.0D) {
            rates.replaceAll((prize, weight) -> 0.0D);
            return rates;
        }
        double sum = total;
        rates.replaceAll((prize, weight) -> weight / sum);
        return rates;
    }

    // ------------------------------------------------------------------ 抽取核心

    /** 单次抽取：保底判定 + 权重轮盘 + 最低品质保证。 */
    private Rolled roll(Player player, Pool pool, PlayerData data, int pullSize) {
        List<Prize> candidates = available(player, pool, data);
        if (candidates.isEmpty()) {
            return null;
        }

        // 1) 保底：命中阈值则强制从「目标品质或更高」中抽取。
        //    多条规则同时就绪时取品质最高的那条，否则低品质保底会一直抢先，
        //    高品质保底永远兑现不了。
        Pool.PityRule ready = PityEngine.selectReady(pool, data);
        if (ready != null) {
            List<Prize> guaranteed = PityEngine.guaranteedCandidates(candidates, ready.rarity());
            if (!guaranteed.isEmpty()) {
                return new Rolled(guaranteed.getFirst(), ready);
            }
            // 就绪却无法兑现：说明该品质的奖品全部达到限额（或没有该品质的奖品）。
            // 此时必须告警，否则玩家会看到「超过保底却不出货」且计数一路增长。
            warnUnfulfillablePity(player, pool, ready);
        }

        Prize chosen = pick(candidates, player, pool);

        // 2) 最低品质保证（每次必得 / 连抽必得）
        String guarantee = pullSize > 1 ? pool.tenPullRarity() : pool.guaranteeRarity();
        if (guarantee != null && !rarityAtLeast(chosen, guarantee)) {
            List<Prize> floor = candidates.stream().filter(p -> rarityAtLeast(p, guarantee)).toList();
            if (!floor.isEmpty()) {
                chosen = pick(floor, player, pool);
            }
        }
        return new Rolled(chosen, null);
    }

    /** 保底无法兑现时输出可诊断的警告（限流，避免刷屏）。 */
    private void warnUnfulfillablePity(Player player, Pool pool, Pool.PityRule rule) {
        String key = pool.id() + '|' + rule.rarity();
        long now = System.currentTimeMillis();
        Long last = pityWarnedAt.get(key);
        if (last != null && now - last < 60_000L) {
            return;
        }
        pityWarnedAt.put(key, now);
        plugin.getLogger().warning("卡池 " + pool.id() + " 的 " + rule.rarity()
                + " 保底已就绪，但当前没有任何该品质（或更高）的奖品可发放："
                + "请检查该品质奖品的 limit / player-limit / daily-limit 是否已用尽，"
                + "或该品质是否存在奖品。玩家：" + player.getName());
    }

    /** 当前可参与轮盘的奖品（满足条件且未达上限）。 */
    private List<Prize> available(Player player, Pool pool, PlayerData data) {
        List<Prize> list = new ArrayList<>();
        for (Prize prize : pool.prizes()) {
            if (!prize.test(player)) {
                continue;
            }
            boolean ok = prize.available(
                    ctx.data()::globalCount,
                    data::prizeCount,
                    data::dailyCount
            );
            if (ok) {
                list.add(prize);
            }
        }
        return list;
    }

    /** 权重轮盘。 */
    private Prize pick(List<Prize> candidates, Player player, Pool pool) {
        double global = ctx.settings().globalBonus(player);
        double total = 0.0D;
        double[] weights = new double[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            weights[i] = candidates.get(i).effectiveWeight(player, pool.bonusWeight(), global);
            total += weights[i];
        }
        if (total <= 0.0D) {
            return candidates.get(random.nextInt(candidates.size()));
        }
        double roll = random.nextDouble() * total;
        double cursor = 0.0D;
        for (int i = 0; i < candidates.size(); i++) {
            cursor += weights[i];
            if (roll < cursor) {
                return candidates.get(i);
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    /** 轮盘中最小的可用权重，用于“一发入魂”判定。 */
    private double minWeight(Player player, Pool pool, PlayerData data) {
        double min = Double.MAX_VALUE;
        double global = ctx.settings().globalBonus(player);
        for (Prize prize : available(player, pool, data)) {
            min = Math.min(min, prize.effectiveWeight(player, pool.bonusWeight(), global));
        }
        return min == Double.MAX_VALUE ? 0.0D : min;
    }

    /** 保底判定：返回需要强制的规则，未触发返回 null。 */
    private Pool.PityRule pityRule(Pool pool, PlayerData data) {
        return PityEngine.selectReady(pool, data);
    }

    /** 记录中奖：更新保底计数与统计（保底语义集中在 {@link PityEngine}）。 */
    private void registerWin(Pool pool, PlayerData data, Prize prize) {
        PityEngine.applyWin(pool, data, prize);
        data.addPrizeCount(pool.id(), prize.id(), 1);
        data.addDailyCount(prize.id(), 1);
        ctx.data().addGlobalCount(prize.id(), 1);
    }

    private boolean rarityAtLeast(Prize prize, String rarity) {
        return PityEngine.atLeast(prize, rarity);
    }

    private PlaceholderContext costContext(PlaceholderContext base, Currency currency, Player player, double cost) {
        return base
                .raw("currency", Text.plain(currency.displayName()))
                .num("cost", cost)
                .num("balance", currency.balance(player));
    }

    // ------------------------------------------------------------------ 提示与播报

    private void notifyResult(DrawOutcome result, Pool pool, Currency currency, String currencyId) {
        Player player = result.player();
        Settings settings = ctx.settings();
        Messages messages = ctx.messages();
        String currencyName = ctx.currencies().displayName(currency, currencyId);
        double balance = ctx.currencies().balanceOf(currency, player);

        PlaceholderContext ph = PlaceholderContext.of(player)
                .pool(pool)
                .num("cost", result.totalCost())
                .raw("currency", currencyName)
                .num("count", result.prizes().size())
                .num("amount", result.amount())
                .num("dropped", result.dropped())
                .num("balance", balance)
                .raw("summary", RewardApplier.summary(result.prizes(), result.counts()));

        if (result.dropped() > 0) {
            messages.send(player, "draw.bag-full", player, ph);
        }
        messages.send(player, Messages.K.DRAW_SUCCESS, player, ph);

        Prize best = result.bestPrize();
        if (result.pityHit()) {
            messages.send(player, Messages.K.PITY_TRIGGERED, player, ph.prize(best));
        }
        if (result.lucky() && best != null) {
            messages.send(player, Messages.K.LUCKY, player, ph.prize(best));
        }

        if (settings.soundEnabled()) {
            try {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8F,
                        result.lucky() ? 0.6F : 1.2F);
            } catch (Throwable ignored) {
                // 音效失败不影响抽奖
            }
        }

        if (!settings.broadcastEnabled() || best == null) {
            return;
        }
        boolean announce = pool.announcedRarities().stream().anyMatch(r -> r.equalsIgnoreCase(best.rarity()));
        if (!announce && !result.lucky()) {
            return;
        }
        PlaceholderContext broadcastCtx = PlaceholderContext.of(player)
                .pool(pool)
                .prize(best)
                .raw("rarity_color", Messages.rarityColor(best.rarity()));
        String path = result.lucky() ? Messages.K.BROADCAST_LUCKY : Messages.K.BROADCAST;
        String text = messages.broadcast(path, player, broadcastCtx);
        if (text == null || text.isBlank()) {
            return;
        }
        net.kyori.adventure.text.Component component = Text.mm(text);
        for (Player online : Bukkit.getOnlinePlayers()) {
            Text.send(online, component);
        }
        if (settings.broadcastToConsole()) {
            Text.send(Bukkit.getConsoleSender(), component);
        }
    }

    // ------------------------------------------------------------------ 辅助

    public boolean isBusy(Player player) {
        return busy.contains(player.getUniqueId());
    }

    /** 清除抽奖冷却（管理命令 / 活动期间使用）。 */
    public void clearCooldown(Player player) {
        if (player != null) {
            cooldownUntil.remove(player.getUniqueId());
        }
    }

    /** 剩余冷却毫秒数，0 表示可以抽奖。 */
    public long cooldownRemaining(Player player) {
        if (player == null) {
            return 0L;
        }
        return Math.max(0L, cooldownUntil.getOrDefault(player.getUniqueId(), 0L) - System.currentTimeMillis());
    }

    public void rememberPending(Player player, String poolId, int amount) {
        pending.put(player.getUniqueId(), new Pending(poolId, amount, System.currentTimeMillis()));
    }

    /** 读取待确认请求（超过 2 分钟自动失效）。 */
    public Pending pending(Player player) {
        Pending value = pending.get(player.getUniqueId());
        if (value == null) {
            return null;
        }
        if (System.currentTimeMillis() - value.createdAt() > 120_000L) {
            pending.remove(player.getUniqueId());
            return null;
        }
        return value;
    }

    public void clearPending(Player player) {
        pending.remove(player.getUniqueId());
    }

    public boolean usable(Player player, Pool pool) {
        return pool != null && pool.isUsable(player) && !pool.empty();
    }

    /**
     * 取玩家在某卡池的保底进度快照，供界面展示。
     * <p>
     * 返回值中的 current 已经做上限收敛，因此界面永远不会出现「31/30」这类脏数据。
     *
     * @return 每个保底规则一条记录，顺序与配置一致
     */
    public List<PityProgress> pityProgress(Player player, Pool pool) {
        if (player == null || pool == null || pool.pityRules().isEmpty()) {
            return List.of();
        }
        PlayerData data = ctx.data().get(player.getUniqueId(), player.getName());
        List<PityProgress> list = new ArrayList<>(pool.pityRules().size());
        for (Pool.PityRule rule : pool.pityRules()) {
            int threshold = PityEngine.threshold(rule);
            // 统一走引擎读取，保证界面显示与判定逻辑用的是同一份数据
            int current = Math.min(data.counter(pool.id(), rule.rarity()), threshold);
            list.add(new PityProgress(rule.rarity(), current, threshold,
                    PityEngine.isReady(rule, data.counter(pool.id(), rule.rarity())),
                    data.total(pool.id())));
        }
        return list;
    }

    /** 界面用的保底进度。 */
    public record PityProgress(String rarity, int current, int threshold, boolean ready, int totalPulls) {

        /** 进度比例 0~1，用于进度条。 */
        public double ratio() {
            return threshold <= 0 ? 0.0D : Math.min(1.0D, (double) current / threshold);
        }

        /** 还差多少次触发保底。 */
        public int remaining() {
            return Math.max(0, threshold - current);
        }
    }

    /** 抽奖结果。 */
    public sealed interface Outcome {

        record Success(DrawOutcome result) implements Outcome {
        }

        record Failure(String messagePath, PlaceholderContext context) implements Outcome {
        }
    }

    /** 绑定抽奖动画引擎（由 UiManager 在构造时注入，可为 null）。 */
    public void bindAnimation(cn.dsh.lottery.animation.AnimationRunner runner) {
        this.animation = runner;
    }
}
