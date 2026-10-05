package cn.dsh.lottery;

import cn.dsh.lottery.config.Messages;
import cn.dsh.lottery.model.Pool;
import cn.dsh.lottery.model.Prize;
import cn.dsh.lottery.util.PlaceholderContext;
import cn.dsh.lottery.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 命令处理：{@code /lottery}。
 * <pre>
 *   /lottery                     打开对话框主界面
 *   /lottery open [卡池]          打开指定卡池界面
 *   /lottery draw [卡池] [次数]    直接抽奖（仍受二次确认配置影响）
 *   /lottery pity [卡池]          查看保底进度
 *   /lottery rates [卡池]         查看概率公示
 *   /lottery history [卡池]       查看我的抽奖记录
 *   /lottery reload              重载配置
 *   /lottery give &lt;玩家&gt; &lt;卡池&gt; &lt;奖品&gt; [数量]   发放奖品
 *   /lottery reset &lt;玩家|*&gt; [卡池]            重置保底与统计
 *   /lottery info                查看运行状态
 * </pre>
 */
public final class LotteryCommand implements CommandExecutor, TabCompleter {

    private final PaperLotteryPlugin plugin;
    private final Supplier<PluginContext> contextSupplier;

    public LotteryCommand(PaperLotteryPlugin plugin, Supplier<PluginContext> contextSupplier) {
        this.plugin = plugin;
        this.contextSupplier = contextSupplier;
    }

    private PluginContext ctx() {
        return contextSupplier.get();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        PluginContext ctx = ctx();
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                Text.sendAny(sender, "<red>控制台请使用 /lottery help 查看用法。");
                return true;
            }
            if (!player.hasPermission("paperlottery.use")) {
                ctx.messages().send(player, Messages.K.NO_PERMISSION, player, PlaceholderContext.of(player));
                return true;
            }
            ctx.ui().openMain(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help", "?" -> help(sender);
            case "open", "gui", "menu" -> open(sender, args);
            case "draw", "pull", "roll" -> draw(sender, args);
            case "pity", "baodi" -> pity(sender, args);
            case "rates", "rate", "prob", "gailv" -> rates(sender, args);
            case "history", "log", "record" -> history(sender, args);
            case "reload", "rl" -> reload(sender);
            case "give" -> give(sender, args);
            case "reset" -> reset(sender, args);
            case "info" -> info(sender);
            default -> ctx.messages().send(sender, Messages.K.UNKNOWN_SUB, asPlayer(sender), PlaceholderContext.of());
        }
        return true;
    }

    // ------------------------------------------------------------------ 各子命令

    private void help(CommandSender sender) {
        List<String> lines = List.of(
                "<gold>PaperLottery <gray>— Paper 26.2 Dialog 抽奖插件",
                "<yellow>/lottery <gray>打开抽奖界面",
                "<yellow>/lottery open [卡池] <gray>打开指定卡池",
                "<yellow>/lottery draw [卡池] [次数] <gray>直接抽奖",
                "<yellow>/lottery pity [卡池] <gray>查看保底进度",
                "<yellow>/lottery rates [卡池] <gray>查看概率公示",
                "<yellow>/lottery history [卡池] <gray>查看抽奖记录",
                "<yellow>/lottery give <玩家> <卡池> <奖品> [数量] <gray>发放奖品",
                "<yellow>/lottery reset <玩家|*> [卡池] <gray>重置保底与统计",
                "<yellow>/lottery reload <gray>重载配置文件",
                "<yellow>/lottery info <gray>查看运行状态与货币列表"
        );
        for (String line : lines) {
            Text.sendAny(sender, line);
        }
    }

    private void open(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        PluginContext ctx = ctx();
        if (!player.hasPermission("paperlottery.use")) {
            ctx.messages().send(player, Messages.K.NO_PERMISSION, player, PlaceholderContext.of(player));
            return;
        }
        Pool pool = args.length > 1 ? ctx.config().pool(args[1]) : ctx.config().firstUsable(player);
        if (pool == null || (args.length > 1 && ctx.config().pools().get(args[1].toLowerCase(Locale.ROOT)) == null)) {
            ctx.messages().send(player, Messages.K.UNKNOWN_POOL, player,
                    PlaceholderContext.of(player).raw("pool", args.length > 1 ? args[1] : "?"));
            return;
        }
        ctx.ui().openMain(player, pool);
    }

    private void draw(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        PluginContext ctx = ctx();
        if (!player.hasPermission("paperlottery.draw")) {
            ctx.messages().send(player, Messages.K.NO_PERMISSION, player, PlaceholderContext.of(player));
            return;
        }
        Pool pool = args.length > 1 ? ctx.config().pool(args[1]) : ctx.config().firstUsable(player);
        if (pool == null) {
            ctx.messages().send(player, Messages.K.NO_POOLS, player, PlaceholderContext.of(player));
            return;
        }
        int amount = ctx.settings().defaultAmount();
        if (args.length > 2) {
            try {
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                amount = ctx.settings().defaultAmount();
            }
        }
        ctx.ui().draw(player, pool, amount);
    }

    private void pity(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        PluginContext ctx = ctx();
        Pool pool = args.length > 1 ? ctx.config().pool(args[1]) : ctx.config().firstUsable(player);
        if (pool == null) {
            ctx.messages().send(player, Messages.K.NO_POOLS, player, PlaceholderContext.of(player));
            return;
        }
        ctx.ui().openPity(player, pool);
    }

    private void rates(CommandSender sender, String[] args) {
        PluginContext ctx = ctx();
        Player player = asPlayer(sender);
        Pool pool = args.length > 1 ? ctx.config().pool(args[1])
                : (player != null ? ctx.config().firstUsable(player) : ctx.config().defaultPool());
        if (pool == null) {
            ctx.messages().send(sender, Messages.K.NO_POOLS, player, PlaceholderContext.of());
            return;
        }
        if (player != null) {
            ctx.ui().openRates(player, pool);
            return;
        }
        // 控制台输出文本版概率表
        Text.sendAny(sender, "<gold>卡池 " + pool.id() + " 概率公示：");
        ctx.service().rates(pool).forEach((prize, rate) ->
                Text.sendAny(sender, "  " + Messages.rarityColor(prize.rarity()) + prize.displayName()
                        + " <dark_gray>- <white>" + String.format(Locale.ROOT, "%.3f%%", rate * 100.0D)
                        + " <dark_gray>(权重 " + PlaceholderContext.number(prize.weight()) + ")"));
    }

    private void history(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        PluginContext ctx = ctx();
        Pool pool = args.length > 1 ? ctx.config().pool(args[1]) : ctx.config().firstUsable(player);
        if (pool == null) {
            ctx.messages().send(player, Messages.K.NO_POOLS, player, PlaceholderContext.of(player));
            return;
        }
        ctx.ui().openHistory(player, pool);
    }

    private void reload(CommandSender sender) {
        PluginContext ctx = ctx();
        if (!sender.hasPermission("paperlottery.admin")) {
            ctx.messages().send(sender, Messages.K.NO_PERMISSION, asPlayer(sender), PlaceholderContext.of());
            return;
        }
        plugin.reloadAll();
        PluginContext fresh = ctx();
        fresh.messages().send(sender, Messages.K.RELOADED, asPlayer(sender),
                PlaceholderContext.of().num("pools", fresh.config().pools().size()));
    }

    private void give(CommandSender sender, String[] args) {
        PluginContext ctx = ctx();
        if (!sender.hasPermission("paperlottery.admin")) {
            ctx.messages().send(sender, Messages.K.NO_PERMISSION, asPlayer(sender), PlaceholderContext.of());
            return;
        }
        if (args.length < 4) {
            Text.sendAny(sender, "<red>用法：/lottery give <玩家> <卡池> <奖品> [数量]");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null || (!target.isOnline() && !target.hasPlayedBefore())) {
            ctx.messages().send(sender, Messages.K.PLAYER_NOT_FOUND, asPlayer(sender),
                    PlaceholderContext.of().raw("target", args[1]));
            return;
        }
        Player online = target.getPlayer();
        if (online == null) {
            Text.sendAny(sender, "<red>该玩家当前不在线，物品类奖品无法离线发放。");
            return;
        }
        Pool pool = ctx.config().pools().get(args[2].toLowerCase(Locale.ROOT));
        if (pool == null) {
            ctx.messages().send(sender, Messages.K.UNKNOWN_POOL, asPlayer(sender),
                    PlaceholderContext.of().raw("pool", args[2]));
            return;
        }
        Prize prize = pool.prize(args[3]);
        if (prize == null) {
            ctx.messages().send(sender, Messages.K.GIVE_FAILED, asPlayer(sender),
                    PlaceholderContext.of(online).pool(pool).raw("prize", args[3]));
            return;
        }
        int amount = 1;
        if (args.length > 4) {
            try {
                amount = Math.max(1, Integer.parseInt(args[4]));
            } catch (NumberFormatException ignored) {
                amount = 1;
            }
        }
        boolean ok = plugin.givePrize(online, pool, prize, amount);
        PlaceholderContext ph = PlaceholderContext.of(online).pool(pool).prize(prize).num("amount", amount)
                .raw("target", online.getName());
        if (ok) {
            ctx.messages().send(sender, Messages.K.GIVE_OTHER, asPlayer(sender), ph);
        } else {
            ctx.messages().send(sender, Messages.K.GIVE_FAILED, asPlayer(sender), ph);
        }
    }

    private void reset(CommandSender sender, String[] args) {
        PluginContext ctx = ctx();
        if (!sender.hasPermission("paperlottery.admin")) {
            ctx.messages().send(sender, Messages.K.NO_PERMISSION, asPlayer(sender), PlaceholderContext.of());
            return;
        }
        if (args.length < 2) {
            Text.sendAny(sender, "<red>用法：/lottery reset <玩家|*> [卡池]");
            return;
        }
        String poolId = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : null;
        Pool pool = poolId == null ? null : ctx.config().pools().get(poolId);
        if ("*".equals(args[1])) {
            List<cn.dsh.lottery.data.PlayerData> all = new ArrayList<>(ctx.data().all());
            for (var data : all) {
                if (pool == null) {
                    data.resetAll();
                } else {
                    data.resetPool(pool.id());
                }
            }
            plugin.dataStore().markDirty();
            Text.sendAny(sender, "<green>已重置 <white>" + all.size() + " <green>名玩家的数据。");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null) {
            ctx.messages().send(sender, Messages.K.PLAYER_NOT_FOUND, asPlayer(sender),
                    PlaceholderContext.of().raw("target", args[1]));
            return;
        }
        var data = ctx.data().get(target.getUniqueId(), target.getName() == null ? args[1] : target.getName());
        if (pool == null) {
            data.resetAll();
        } else {
            data.resetPool(pool.id());
        }
        plugin.dataStore().markDirty();
        PlaceholderContext ph = PlaceholderContext.of().raw("target", data.name())
                .raw("pool_name", pool == null ? "全部" : Text.plain(pool.displayName()));
        ctx.messages().send(sender, Messages.K.RESET_DONE, asPlayer(sender), ph);
    }

    private void info(CommandSender sender) {
        PluginContext ctx = ctx();
        Text.sendAny(sender, "<gold>PaperLottery <gray>运行状态");
        Text.sendAny(sender, "<gray>服务端：<white>" + Bukkit.getVersion());
        Text.sendAny(sender, "<gray>Dialog API：<white>" + (ctx.ui().supported() ? "可用" : "不可用"));
        Text.sendAny(sender, "<gray>Vault 经济：<white>" + (ctx.currencies().hasVault() ? "已接入" : "未接入"));
        Text.sendAny(sender, "<gray>货币数量：<white>" + ctx.currencies().size());
        for (var currency : ctx.currencies().all()) {
            Text.sendAny(sender, "  <dark_gray>- <white>" + currency.id()
                    + " <dark_gray>[" + currency.type().label() + "] "
                    + (currency.isAvailable() ? "<green>可用" : "<red>不可用"));
        }
        Text.sendAny(sender, "<gray>卡池数量：<white>" + ctx.config().pools().size());
        for (Pool pool : ctx.config().pools().values()) {
            String daily = pool.hasDailyDrawLimit() ? (", 每日上限 " + pool.dailyDrawLimit() + " 次") : "";
            Text.sendAny(sender, "  <dark_gray>- <white>" + pool.id() + " <dark_gray>("
                    + Text.plain(pool.displayName()) + ", 奖品 " + pool.prizes().size() + " 个, 货币 "
                    + pool.currencyId() + daily + ")");
        }
        Text.sendAny(sender, "<gray>追踪玩家：<white>" + ctx.data().trackedPlayers());
    }

    // ------------------------------------------------------------------ Tab 补全

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        PluginContext ctx = ctx();
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            for (String sub : List.of("open", "draw", "pity", "rates", "history", "reload", "give", "reset", "info", "help")) {
                if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    result.add(sub);
                }
            }
            return result;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "open", "draw", "pity", "rates", "history" -> {
                if (args.length == 2) {
                    for (String id : ctx.config().pools().keySet()) {
                        if (id.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                            result.add(id);
                        }
                    }
                } else if (args.length == 3 && sub.equals("draw")) {
                    for (int amount : ctx.settings().amountOptions()) {
                        result.add(String.valueOf(amount));
                    }
                }
            }
            case "give" -> {
                if (args.length == 2) {
                    Bukkit.getOnlinePlayers().forEach(p -> result.add(p.getName()));
                } else if (args.length == 3) {
                    result.addAll(ctx.config().pools().keySet());
                } else if (args.length == 4) {
                    Pool pool = ctx.config().pools().get(args[2].toLowerCase(Locale.ROOT));
                    if (pool != null) {
                        pool.prizes().forEach(p -> result.add(p.id()));
                    }
                } else if (args.length == 5) {
                    result.addAll(List.of("1", "5", "10"));
                }
            }
            case "reset" -> {
                if (args.length == 2) {
                    result.add("*");
                    Bukkit.getOnlinePlayers().forEach(p -> result.add(p.getName()));
                } else if (args.length == 3) {
                    result.addAll(ctx.config().pools().keySet());
                }
            }
            default -> {
                // 无补全
            }
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        result.removeIf(value -> !value.toLowerCase(Locale.ROOT).startsWith(last));
        return result;
    }

    // ------------------------------------------------------------------ 工具

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        Text.sendAny(sender, "<red>该命令只能由玩家执行。");
        return null;
    }

    private Player asPlayer(CommandSender sender) {
        return sender instanceof Player player ? player : null;
    }
}
