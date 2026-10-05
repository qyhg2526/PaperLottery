package cn.dsh.lottery.ui;

import cn.dsh.lottery.PaperLotteryPlugin;
import cn.dsh.lottery.PluginContext;
import cn.dsh.lottery.config.Messages;
import cn.dsh.lottery.currency.Currency;
import cn.dsh.lottery.data.PlayerData;
import cn.dsh.lottery.lottery.LotteryService;
import cn.dsh.lottery.lottery.RewardApplier;
import cn.dsh.lottery.model.DrawOutcome;
import cn.dsh.lottery.model.Pool;
import cn.dsh.lottery.model.Prize;
import cn.dsh.lottery.util.PlaceholderContext;
import cn.dsh.lottery.util.Text;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 对话框管理器：集中构建与管理所有基于 Paper Dialog API 的界面。
 * <p>
 * 界面一览：
 * <ul>
 *     <li>{@link #openMain} —— 抽奖主界面（卡池选择 / 连抽次数 / 抽奖按钮 / 保底与概率入口）</li>
 *     <li>{@link #openResult} —— 抽奖结果界面（展示本次所得）</li>
 *     <li>{@link #openRates} —— 概率公示面板</li>
 *     <li>{@link #openPity} —— 保底进度面板</li>
 *     <li>{@link #openHistory} —— 我的抽奖记录</li>
 *     <li>{@link #openConfirm} —— 二次确认</li>
 *     <li>{@link #openAdmin} —— 管理面板（重载配置 / 强制抽奖 / 保底重置）</li>
 * </ul>
 */
public final class DialogManager {

    /** 输入组件的响应 key。 */
    private static final String KEY_POOL = "pool";
    private static final String KEY_AMOUNT = "amount";

    private final PaperLotteryPlugin plugin;
    private final PluginContext ctx;

    public DialogManager(PaperLotteryPlugin plugin, PluginContext ctx) {
        this.plugin = plugin;
        this.ctx = ctx;
    }

    // ================================================================== 主界面

    /**
     * 打开抽奖主界面。
     *
     * @param messageFromPrevious 上一次操作留下的提示（可为空）
     */
    public void openMain(Player player, Pool pool, Integer amount, String messageFromPrevious) {
        Pool target = pool != null ? pool : ctx.config().firstUsable(player);
        if (target == null) {
            ctx.messages().send(player, Messages.K.NO_POOLS, player, PlaceholderContext.of(player));
            return;
        }
        int selectedAmount = amount == null ? defaultAmount(target) : amount;
        show(player, buildMain(player, target, selectedAmount, messageFromPrevious));
    }

    public void openMain(Player player) {
        openMain(player, null, null, null);
    }

    private Dialog buildMain(Player player, Pool pool, int amount, String messageFromPrevious) {
        Messages messages = ctx.messages();
        Currency currency = ctx.currencies().get(pool.currencyId());
        // 货币可能因为 Vault 未安装 / 配置有误而为 null，这里全部做空值兜底，
        // 保证界面一定能打开，并把问题暴露在“余额不足/货币不可用”的提示里。
        String currencyName = ctx.currencies().displayName(currency, pool.currencyId());
        double cost = pool.totalCost(amount);
        double balance = ctx.currencies().balanceOf(currency, player);
        boolean usable = pool.isUsable(player) && !pool.empty();
        boolean currencyReady = currency != null && currency.isAvailable();
        boolean affordable = currencyReady && (cost <= 0 || balance >= cost);

        PlaceholderContext ph = PlaceholderContext.of(player)
                .pool(pool)
                .raw("currency", currencyName)
                .num("cost", cost)
                .raw("cost_text", ctx.currencies().format(currency, cost))
                .num("balance", balance)
                .raw("balance_text", ctx.currencies().format(currency, balance))
                .num("amount", amount)
                .num("pulls", playerPulls(player, pool));

        // ---- 正文 ----
        List<Component> lines = new ArrayList<>();
        if (messageFromPrevious != null && !messageFromPrevious.isBlank()) {
            lines.add(Text.mm(messages.raw("dialog.result-line", "<gray>上次结果：<white>%result%")
                    .replace("%result%", messageFromPrevious)));
            lines.add(Component.empty());
        }
        lines.add(Text.mm(messages.raw("dialog.balance-line",
                        "<gray>我的余额：<white>%balance_text% <dark_gray>(%currency%)")
                .replace("%balance_text%", ctx.currencies().format(currency, balance))
                .replace("%currency%", currencyName)));
        lines.add(Text.mm(messages.raw("dialog.cost-line", "<gray>本次消耗：<white>%cost_text%")
                .replace("%cost_text%", ctx.currencies().format(currency, cost))));
        lines.add(Component.empty());
        lines.addAll(DialogUtil.render(player, pool.description(), ph));

        // 保底进度：统一从 LotteryService 取快照，保证界面与实际计数完全一致
        // （current 已做阈值收敛，不会再出现 31/30 之类脏数据）
        List<LotteryService.PityProgress> pity = ctx.service().pityProgress(player, pool);
        if (!pity.isEmpty()) {
            lines.add(Component.empty());
            lines.add(Text.mm(messages.raw("dialog.pity-header", "<dark_gray>—— 保底进度 ——")));
            for (LotteryService.PityProgress progress : pity) {
                lines.add(Text.mm(messages.raw("dialog.pity-line",
                                "<gray>%rarity_color%%rarity_name%<gray>：<white>%current%<gray>/<white>%threshold% %bar%%ready%")
                        .replace("%rarity_color%", Messages.rarityColor(progress.rarity()))
                        .replace("%rarity_name%", Messages.rarityName(progress.rarity()))
                        .replace("%current%", String.valueOf(progress.current()))
                        .replace("%threshold%", String.valueOf(progress.threshold()))
                        .replace("%bar%", progressBar(progress.current(), progress.threshold()))
                        .replace("%ready%", progress.ready()
                                ? messages.raw("dialog.pity-ready", " <gold>【已就绪】") : "")));
            }
            lines.add(Text.mm(messages.raw("dialog.pity-total",
                            "<dark_gray>本卡池累计抽奖：<white>%total% <dark_gray>次")
                    .replace("%total%", String.valueOf(pity.getFirst().totalPulls()))));
        }

        // 每日抽奖次数上限
        LotteryService.DailyProgress daily = ctx.service().dailyProgress(player, pool);
        if (daily.limited()) {
            lines.add(Text.mm(messages.raw("dialog.daily-limit-line",
                            "<gray>今日次数：<white>%used%<gray>/<white>%limit% <dark_gray>(剩余 %remaining%)")
                    .replace("%used%", String.valueOf(daily.used()))
                    .replace("%limit%", String.valueOf(daily.limit()))
                    .replace("%remaining%", String.valueOf(daily.remaining()))
                    + (daily.bypass() ? messages.raw("dialog.daily-limit-bypass", " <green>[已绕过]") : "")));
        }

        if (!usable) {
            lines.add(Component.empty());
            lines.add(Text.mm(messages.raw("dialog.locked", "<red>✖ 你当前不满足该卡池的参与条件。")));
        } else if (daily.exhausted()) {
            lines.add(Component.empty());
            lines.add(Text.mm(messages.raw("dialog.daily-limit-exhausted",
                            "<red>✖ 今日抽奖次数已用尽（<yellow>%used%<red>/<yellow>%limit%<red>），明天再来。")
                    .replace("%used%", String.valueOf(daily.used()))
                    .replace("%limit%", String.valueOf(daily.limit()))));
        } else if (!currencyReady) {
            lines.add(Component.empty());
            lines.add(Text.mm(messages.raw("dialog.currency-unavailable",
                            "<red>✖ 该卡池的货币 <yellow>%currency%<red> 当前不可用，请联系管理员。")
                    .replace("%currency%", currencyName)));
        } else if (!affordable) {
            lines.add(Component.empty());
            lines.add(Text.mm(messages.raw("dialog.unaffordable", "<red>✖ 余额不足，无法抽奖。")));
        }

        // ---- 输入组件 ----
        List<DialogInput> inputs = new ArrayList<>();
        List<Pool> usablePools = ctx.config().pools().values().stream().filter(p -> p.isUsable(player)).toList();
        if (usablePools.size() > 1) {
            List<DialogUtil.Option> options = usablePools.stream()
                    .map(p -> new DialogUtil.Option(p.id(), Text.mm(p.displayName())))
                    .toList();
            inputs.add(DialogUtil.singleOption(KEY_POOL,
                    Text.mm(messages.raw(Messages.K.DIALOG_POOL_LABEL, "选择卡池")),
                    options, pool.id(), 150));
        }
        List<Integer> amounts = pool.availableAmounts().stream()
                .filter(a -> a <= ctx.settings().maxAmount())
                .toList();
        if (amounts.size() > 1) {
            List<DialogUtil.Option> options = amounts.stream()
                    .map(a -> new DialogUtil.Option(String.valueOf(a), Text.mm(amountLabel(a))))
                    .toList();
            inputs.add(DialogUtil.singleOption(KEY_AMOUNT,
                    Text.mm(messages.raw(Messages.K.DIALOG_AMOUNT_LABEL, "抽奖次数")),
                    options, String.valueOf(amount), 150));
        }

        // ---- 按钮 ----
        List<ActionButton> buttons = new ArrayList<>();
        boolean blockedByDailyLimit = daily.exhausted();
        Component drawLabel = blockedByDailyLimit
                ? Text.mm(messages.raw("dialog.button-draw-limited", "<red>今日次数已用尽"))
                : Text.mm(messages.raw("dialog.button-draw", "<gold>开始抽奖 <gray>(%cost_text%)")
                        .replace("%cost_text%", ctx.currencies().format(currency, cost)));
        Component drawTooltip = blockedByDailyLimit
                ? Text.mm(messages.raw("dialog.button-draw-limited-tooltip",
                        "<gray>今日剩余 %remaining% 次，明天再来")
                        .replace("%remaining%", String.valueOf(daily.remaining())))
                : Text.mm(messages.raw("dialog.button-draw-tooltip",
                        "<gray>点击后按下拉框选择的次数进行抽奖"));
        buttons.add(DialogUtil.callbackButton(drawLabel, drawTooltip, 150, (clicker, response) -> {
            String poolId = DialogUtil.readText(response, KEY_POOL, pool.id());
            int draws = DialogUtil.readInt(response, KEY_AMOUNT, amount);
            Pool selected = ctx.config().pool(poolId);
            handleDrawRequest(clicker, selected == null ? pool : selected, draws);
        }));

        buttons.add(DialogUtil.callbackButton(
                Text.mm(messages.raw("dialog.button-rates", "<aqua>概率公示")),
                Text.mm(messages.raw("dialog.button-rates-tooltip", "<gray>查看各奖品的实时概率")),
                100, (clicker, response) -> {
                    Pool selected = ctx.config().pool(DialogUtil.readText(response, KEY_POOL, pool.id()));
                    openRates(clicker, selected == null ? pool : selected);
                }));

        buttons.add(DialogUtil.callbackButton(
                Text.mm(messages.raw("dialog.button-history", "<yellow>我的记录")),
                Text.mm(messages.raw("dialog.button-history-tooltip", "<gray>查看最近的抽奖流水")),
                100, (clicker, response) -> openHistory(clicker, pool)));

        if (player.hasPermission("paperlottery.admin")) {
            buttons.add(DialogUtil.callbackButton(
                    Text.mm(messages.raw("dialog.button-admin", "<red>管理面板")),
                    Text.mm(messages.raw("dialog.button-admin-tooltip", "<gray>重载配置 / 强制抽奖 / 重置保底")),
                    100, (clicker, response) -> openAdmin(clicker, pool)));
        }

        ActionButton exit = DialogUtil.callbackButton(
                Text.mm(messages.raw("dialog.button-close", "关闭")),
                Text.mm(messages.raw("dialog.button-close-tooltip", "<gray>关闭界面")),
                60, (clicker, response) -> clicker.closeDialog());

        DialogBase base = DialogUtil.base(ctx, Text.mm(pool.displayName()), DialogUtil.text(ctx, lines), inputs);
        DialogType type = DialogType.multiAction(buttons, exit, 2);
        return Dialog.create(factory -> factory.empty().base(base).type(type));
    }

    // ================================================================== 结果界面

    /** 抽奖结果界面。 */
    public void openResult(Player player, DrawOutcome result, Pool pool) {
        List<Component> lines = new ArrayList<>();
        lines.add(Text.mm(ctx.messages().raw("dialog.result-header", "<gold>✦ 抽奖结果 ✦")));
        lines.add(Component.empty());
        for (Prize prize : result.prizes()) {
            int count = result.counts().getOrDefault(prize.id(), 1);
            lines.add(Text.mm(ctx.messages().raw("dialog.result-prize",
                            "%rarity_color%▸ %prize_name% <gray>×<white>%count%    <dark_gray>[%rarity_name%]")
                    .replace("%rarity_color%", Messages.rarityColor(prize.rarity()))
                    .replace("%prize_name%", prize.displayName())
                    .replace("%count%", String.valueOf(count))
                    .replace("%rarity_name%", Messages.rarityName(prize.rarity()))));
        }
        lines.add(Component.empty());
        Currency resultCurrency = ctx.currencies().get(result.currencyId());
        double remaining = ctx.currencies().balanceOf(resultCurrency, player);
        lines.add(Text.mm(ctx.messages().raw("dialog.result-footer",
                        "<gray>本次消耗：<white>%cost_text%<gray>　当前余额：<white>%balance_text%")
                .replace("%cost_text%", ctx.currencies().format(resultCurrency, result.totalCost()))
                .replace("%balance_text%", ctx.currencies().format(resultCurrency, remaining))));
        if (result.dropped() > 0) {
            lines.add(Text.mm(ctx.messages().raw(Messages.K.BAG_FULL,
                            "<yellow>背包空间不足，%dropped% 件奖品已掉落在你脚下。")
                    .replace("%dropped%", String.valueOf(result.dropped()))));
        }
        if (result.pityHit()) {
            lines.add(Text.mm(ctx.messages().raw("dialog.result-pity", "<gold>✨ 本次触发了保底！")));
        }

        List<ActionButton> buttons = new ArrayList<>();
        Pool finalPool = pool;
        int again = result.amount();
        buttons.add(DialogUtil.callbackButton(
                Text.mm(ctx.messages().raw("dialog.button-again", "<gold>再来一次 <gray>(%amount%连)")
                        .replace("%amount%", String.valueOf(again))),
                Text.mm(ctx.messages().raw("dialog.button-again-tooltip", "<gray>以相同次数再抽一次")),
                150, (clicker, response) -> handleDrawRequest(clicker, finalPool, again)));
        buttons.add(DialogUtil.callbackButton(
                Text.mm(ctx.messages().raw("dialog.button-back", "<gray>返回主界面")),
                Component.empty(), 100, (clicker, response) -> openMain(clicker, finalPool, again, null)));

        ActionButton exit = DialogUtil.callbackButton(
                Text.mm(ctx.messages().raw("dialog.button-close", "关闭")),
                Component.empty(), 60, (clicker, response) -> clicker.closeDialog());

        DialogBase base = DialogUtil.base(ctx, Text.mm(ctx.messages().raw("dialog.result-title", "<gold>抽奖结果")),
                DialogUtil.text(ctx, lines));
        DialogType type = DialogType.multiAction(buttons, exit, 2);
        show(player, Dialog.create(factory -> factory.empty().base(base).type(type)));
    }

    // ================================================================== 概率公示

    /** 概率公示面板。 */
    public void openRates(Player player, Pool pool) {
        Map<Prize, Double> rates = ctx.service().rates(pool);
        List<Component> lines = new ArrayList<>();
        lines.add(Text.mm(ctx.messages().raw("dialog.rates-header",
                "<gold>概率公示 <dark_gray>(权重轮盘，实时归一化)")));
        lines.add(Component.empty());
        rates.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .forEach(entry -> {
                    Prize prize = entry.getKey();
                    double percent = entry.getValue() * 100.0D;
                    lines.add(Text.mm(ctx.messages().raw("dialog.rates-line",
                                    "%rarity_color%▸ %prize_name% <dark_gray>| <white>%percent%% <dark_gray>(权重 %weight%)")
                            .replace("%rarity_color%", Messages.rarityColor(prize.rarity()))
                            .replace("%prize_name%", prize.displayName())
                            .replace("%percent%", formatPercent(percent))
                            .replace("%weight%", PlaceholderContext.number(
                                    prize.effectiveWeight(null, pool.bonusWeight(), 1.0D)))));
                });
        lines.add(Component.empty());
        lines.add(Text.mm(ctx.messages().raw("dialog.rates-note",
                "<dark_gray>※ 概率会随保底进度、权限加成与活动倍率动态变化；达到上限的奖品会被移出轮盘。")));
        if (!pool.pityRules().isEmpty()) {
            lines.add(Text.mm(ctx.messages().raw("dialog.rates-pity", "<dark_gray>※ 本卡池存在保底机制。")));
        }
        ActionButton back = DialogUtil.callbackButton(
                Text.mm(ctx.messages().raw(Messages.K.DIALOG_BACK, "返回")),
                Component.empty(), 100, (clicker, response) -> openMain(clicker, pool, null, null));
        DialogBase base = DialogUtil.base(ctx, Text.mm(ctx.messages().raw("dialog.rates-title", "<aqua>概率公示")),
                DialogUtil.text(ctx, lines));
        show(player, Dialog.create(factory -> factory.empty().base(base).type(DialogType.notice(back))));
    }

    // ================================================================== 保底面板

    /** 保底进度面板。 */
    public void openPity(Player player, Pool pool) {
        List<Component> lines = new ArrayList<>();
        lines.add(Text.mm(ctx.messages().raw("dialog.pity-title-line", "<gold>保底进度")));
        lines.add(Text.mm("<dark_gray>卡池：" + Text.plain(pool.displayName())));
        lines.add(Component.empty());
        List<LotteryService.PityProgress> pity = ctx.service().pityProgress(player, pool);
        if (pity.isEmpty()) {
            lines.add(Text.mm(ctx.messages().raw("dialog.pity-none", "<gray>该卡池没有配置保底机制。")));
        } else {
            for (LotteryService.PityProgress progress : pity) {
                lines.add(Text.mm(ctx.messages().raw("dialog.pity-line",
                                "<gray>%rarity_color%%rarity_name%<gray>：<white>%current%<gray>/<white>%threshold% %bar%%ready%")
                        .replace("%rarity_color%", Messages.rarityColor(progress.rarity()))
                        .replace("%rarity_name%", Messages.rarityName(progress.rarity()))
                        .replace("%current%", String.valueOf(progress.current()))
                        .replace("%threshold%", String.valueOf(progress.threshold()))
                        .replace("%bar%", progressBar(progress.current(), progress.threshold()))
                        .replace("%ready%", progress.ready()
                                ? ctx.messages().raw("dialog.pity-ready", " <gold>【已就绪】") : "")));
                lines.add(Text.mm(ctx.messages().raw(progress.ready()
                                ? "dialog.pity-will-guarantee"
                                : "dialog.pity-remain",
                                progress.ready()
                                        ? "<gold>　　下一次抽取必得该品质或更高"
                                        : "<dark_gray>　　还差 <white>%remain% <dark_gray>次必得该品质")
                        .replace("%remain%", String.valueOf(progress.remaining()))));
            }
            lines.add(Component.empty());
            lines.add(Text.mm(ctx.messages().raw("dialog.pity-total",
                            "<gray>本卡池累计抽奖：<white>%total% <gray>次")
                    .replace("%total%", String.valueOf(pity.getFirst().totalPulls()))));
        }
        ActionButton back = DialogUtil.callbackButton(
                Text.mm(ctx.messages().raw(Messages.K.DIALOG_BACK, "返回")),
                Component.empty(), 100, (clicker, response) -> openMain(clicker, pool, null, null));
        DialogBase base = DialogUtil.base(ctx, Text.mm(ctx.messages().raw("dialog.pity-title", "<gold>保底进度")),
                DialogUtil.text(ctx, lines));
        show(player, Dialog.create(factory -> factory.empty().base(base).type(DialogType.notice(back))));
    }

    // ================================================================== 历史记录

    /** 我的抽奖记录。 */
    public void openHistory(Player player, Pool pool) {
        PlayerData data = ctx.data().get(player.getUniqueId(), player.getName());
        List<Component> lines = new ArrayList<>();
        lines.add(Text.mm(ctx.messages().raw("dialog.history-header", "<gold>我的抽奖记录")));
        lines.add(Component.empty());
        var history = data.history();
        if (history.isEmpty()) {
            lines.add(Text.mm(ctx.messages().raw("dialog.history-empty", "<gray>暂无抽奖记录。")));
        } else {
            int index = 0;
            for (var record : history) {
                if (index++ >= 15) {
                    break;
                }
                lines.add(Text.mm(ctx.messages().raw("dialog.history-line",
                                "<dark_gray>%time% <gray>[%pool%] <white>%amount%连 <dark_gray>→ <gray>%summary%")
                        .replace("%time%", formatTime(record.time()))
                        .replace("%pool%", record.pool())
                        .replace("%amount%", String.valueOf(record.amount()))
                        .replace("%summary%", record.summary())));
            }
        }
        ActionButton back = DialogUtil.callbackButton(
                Text.mm(ctx.messages().raw(Messages.K.DIALOG_BACK, "返回")),
                Component.empty(), 100, (clicker, response) -> openMain(clicker, pool, null, null));
        DialogBase base = DialogUtil.base(ctx, Text.mm(ctx.messages().raw("dialog.history-title", "<yellow>我的记录")),
                DialogUtil.text(ctx, lines));
        show(player, Dialog.create(factory -> factory.empty().base(base).type(DialogType.notice(back))));
    }

    // ================================================================== 二次确认

    /** 抽奖二次确认。 */
    public void openConfirm(Player player, Pool pool, int amount) {
        Currency currency = ctx.currencies().get(pool.currencyId());
        double cost = pool.totalCost(amount);
        double balance = ctx.currencies().balanceOf(currency, player);
        PlaceholderContext ph = PlaceholderContext.of(player).pool(pool)
                .num("amount", amount)
                .raw("currency", ctx.currencies().displayName(currency, pool.currencyId()))
                .raw("cost_text", ctx.currencies().format(currency, cost))
                .num("balance", balance)
                .raw("balance_text", ctx.currencies().format(currency, balance));

        List<Component> lines = DialogUtil.render(player, ctx.messages().lines("dialog.confirm-body"), ph);
        if (lines.isEmpty()) {
            lines = List.of(
                    Text.mm("<gray>卡池：<white>" + Text.plain(pool.displayName())),
                    Text.mm("<gray>次数：<white>" + amount + " 连"),
                    Text.mm("<gray>消耗：<white>" + ctx.currencies().format(currency, cost)),
                    Text.mm("<gray>余额：<white>" + ctx.currencies().format(currency, balance))
            );
        }

        ActionButton yes = DialogUtil.callbackButton(
                Text.mm(ctx.messages().raw("dialog.confirm-yes", "<green>确认抽奖")),
                Text.mm(ctx.messages().raw("dialog.confirm-yes-tooltip", "<gray>立即消耗并抽奖")),
                150, (clicker, response) -> performDraw(clicker, pool, amount));
        ActionButton no = DialogUtil.callbackButton(
                Text.mm(ctx.messages().raw("dialog.confirm-no", "<red>再想想")),
                Component.empty(), 100, (clicker, response) -> openMain(clicker, pool, amount, null));

        DialogBase base = DialogUtil.base(ctx,
                Text.mm(ctx.messages().raw("dialog.confirm-title", "<gold>确认抽奖")),
                DialogUtil.text(ctx, lines));
        DialogType type = DialogType.confirmation(yes, no);
        show(player, Dialog.create(factory -> factory.empty().base(base).type(type)));
    }

    // ================================================================== 管理面板

    /** 管理面板：重载配置、强制抽奖、重置保底。 */
    public void openAdmin(Player player, Pool pool) {
        List<Component> lines = new ArrayList<>();
        lines.add(Text.mm("<gold>管理面板"));
        lines.add(Component.empty());
        lines.add(Text.mm("<gray>当前卡池：<white>" + Text.plain(pool.displayName()) + " <dark_gray>(" + pool.id() + ")"));
        lines.add(Text.mm("<gray>奖品数量：<white>" + pool.prizes().size()));
        lines.add(Text.mm("<gray>已追踪玩家：<white>" + ctx.data().trackedPlayers()));
        lines.add(Text.mm("<gray>Vault 经济：<white>" + (ctx.currencies().hasVault() ? "已接入" : "未接入")));
        lines.add(Text.mm("<gray>可用货币：<white>" + ctx.currencies().size() + " 种"));

        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(DialogUtil.callbackButton(
                Text.mm("<yellow>重载配置"),
                Text.mm("<gray>重新读取 config.yml"),
                100, (clicker, response) -> {
                    plugin.reloadAll();
                    Text.send(clicker, "<green>配置已重载。");
                    openAdmin(clicker, ctx.config().pool(pool.id()));
                }));
        buttons.add(DialogUtil.callbackButton(
                Text.mm("<aqua>强制抽奖 ×1"),
                Text.mm("<gray>忽略余额直接抽一次（用于测试）"),
                100, (clicker, response) -> {
                    List<Prize> prizes = ctx.service().preview(clicker, pool, 1);
                    StringBuilder sb = new StringBuilder("<gray>模拟结果：");
                    for (Prize prize : prizes) {
                        sb.append(Messages.rarityColor(prize.rarity())).append(prize.displayName()).append(" ");
                    }
                    Text.send(clicker, sb.toString());
                    openAdmin(clicker, pool);
                }));
        buttons.add(DialogUtil.callbackButton(
                Text.mm("<red>重置我的保底"),
                Text.mm("<gray>清空当前卡池的保底计数"),
                100, (clicker, response) -> {
                    ctx.data().get(clicker.getUniqueId(), clicker.getName()).resetPool(pool.id());
                    ctx.data().markDirty();
                    Text.send(clicker, "<green>已重置卡池 " + pool.id() + " 的保底计数。");
                    openAdmin(clicker, pool);
                }));
        buttons.add(DialogUtil.callbackButton(
                Text.mm("<yellow>重置今日次数"),
                Text.mm("<gray>清零当前卡池今天的抽奖次数"),
                100, (clicker, response) -> {
                    ctx.service().resetDailyDraws(clicker, pool);
                    Text.send(clicker, "<green>已清零卡池 " + pool.id() + " 今日的抽奖次数。");
                    openAdmin(clicker, pool);
                }));
        buttons.add(DialogUtil.callbackButton(
                Text.mm("<gray>返回主界面"),
                Component.empty(), 100, (clicker, response) -> openMain(clicker, pool, null, null)));

        ActionButton exit = DialogUtil.callbackButton(
                Text.mm(ctx.messages().raw("dialog.button-close", "关闭")),
                Component.empty(), 60, (clicker, response) -> clicker.closeDialog());
        DialogBase base = DialogUtil.base(ctx, Text.mm("<red>抽奖管理面板"), DialogUtil.text(ctx, lines));
        DialogType type = DialogType.multiAction(buttons, exit, 2);
        show(player, Dialog.create(factory -> factory.empty().base(base).type(type)));
    }

    // ================================================================== 抽奖流程

    /** 处理来自对话框的抽奖请求：按配置决定是否二次确认。 */
    public void handleDrawRequest(Player player, Pool pool, int amount) {
        if (pool == null) {
            ctx.messages().send(player, Messages.K.NO_POOLS, player, PlaceholderContext.of(player));
            return;
        }
        if (!player.hasPermission("paperlottery.draw") && !player.hasPermission("paperlottery.admin")) {
            ctx.messages().send(player, Messages.K.NO_PERMISSION, player, PlaceholderContext.of(player).pool(pool));
            return;
        }
        int draws = Math.max(1, Math.min(amount, ctx.settings().maxAmount()));
        if (ctx.settings().requireConfirmation()) {
            ctx.service().rememberPending(player, pool.id(), draws);
            openConfirm(player, pool, draws);
            return;
        }
        performDraw(player, pool, draws);
    }

    /**
     * 执行一次抽奖的完整流程：校验 → 扣费抽奖 → 播放动画 → 展示结果界面。
     * <p>
     * 抽奖结果在动画开始前就已确定（{@link LotteryService#draw}），动画只是演出；
     * 奖励在动画结束后由 {@link LotteryService#complete} 统一播报与触发事件。
     */
    public void performDraw(Player player, Pool pool, int amount) {
        ctx.service().clearPending(player);
        LotteryService.Outcome outcome = ctx.service().draw(player, pool, amount);
        if (outcome instanceof LotteryService.Outcome.Failure failure) {
            ctx.messages().send(player, failure.messagePath(), player, failure.context());
            openMain(player, pool, amount, null);
            return;
        }
        DrawOutcome result = ((LotteryService.Outcome.Success) outcome).result();
        if (ctx.animation() != null) {
            // 动画结束后再收尾（提示/播报/事件）并打开结果界面
            ctx.animation().play(result, () -> {
                ctx.service().complete(result);
                if (player.isOnline()) {
                    playResultSound(player, result);
                    openResult(player, result, pool);
                }
            });
            return;
        }
        ctx.service().complete(result);
        playResultSound(player, result);
        openResult(player, result, pool);
    }

    /** 从命令行触发抽奖（跳过确认界面）。 */
    public void drawFromCommand(Player player, Pool pool, int amount) {
        performDraw(player, pool, amount);
    }

    // ================================================================== 工具

    private void show(Player player, Dialog dialog) {
        if (player == null || !player.isOnline()) {
            return;
        }
        player.showDialog(dialog);
    }

    private void playResultSound(Player player, DrawOutcome result) {
        if (!ctx.settings().soundEnabled()) {
            return;
        }
        try {
            if (result.pityHit() || result.lucky()) {
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.0F);
            } else {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8F, 1.4F);
            }
        } catch (Throwable ignored) {
            // 忽略音效异常
        }
    }

    private int defaultAmount(Pool pool) {
        int configured = ctx.settings().defaultAmount();
        List<Integer> available = pool.availableAmounts();
        if (available.contains(configured)) {
            return configured;
        }
        return available.isEmpty() ? 1 : available.get(0);
    }

    private int playerPulls(Player player, Pool pool) {
        return ctx.data().get(player.getUniqueId(), player.getName()).total(pool.id());
    }

    private String amountLabel(int amount) {
        return ctx.messages().raw("dialog.amount-option", "%amount% 连").replace("%amount%", String.valueOf(amount));
    }

    /** 生成进度条，例如 ▉▉▉▉▉▉▉░░░。 */
    public static String progressBar(int current, int max) {
        int slots = 10;
        int filled = max <= 0 ? 0 : (int) Math.round(Math.min(1.0D, (double) current / max) * slots);
        StringBuilder sb = new StringBuilder("<dark_gray>[<gold>");
        for (int i = 0; i < slots; i++) {
            if (i == filled) {
                sb.append("<dark_gray>");
            }
            sb.append('▉');
        }
        sb.append("<dark_gray>]");
        return sb.toString();
    }

    private String formatPercent(double percent) {
        if (percent >= 10.0D) {
            return String.format(Locale.ROOT, "%.1f", percent);
        }
        if (percent >= 1.0D) {
            return String.format(Locale.ROOT, "%.2f", percent);
        }
        return String.format(Locale.ROOT, "%.3f", percent);
    }

    private String formatTime(long millis) {
        return java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochMilli(millis));
    }

    /** 供外部拼装提示语使用。 */
    public String summarise(DrawOutcome result) {
        return RewardApplier.summary(result.prizes(), result.counts());
    }
}
