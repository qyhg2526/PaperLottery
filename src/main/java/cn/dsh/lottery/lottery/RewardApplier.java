package cn.dsh.lottery.lottery;

import cn.dsh.lottery.currency.Currency;
import cn.dsh.lottery.currency.CurrencyManager;
import cn.dsh.lottery.model.Pool;
import cn.dsh.lottery.model.Prize;
import cn.dsh.lottery.util.PlaceholderContext;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 奖励发放器：把抽中的奖品真正交付给玩家（物品 / 货币 / 命令）。
 */
public final class RewardApplier {

    /** 发放结果。 */
    public record Applied(int given, int dropped) {
    }

    private RewardApplier() {
    }

    /**
     * 发放全部奖励。
     *
     * @param plugin       插件实例（用于调度命令）
     * @param currencies   货币管理器
     * @param player       目标玩家
     * @param pool         来源卡池
     * @param prize        奖品
     * @param dropWhenFull 背包满时是否掉落在地上
     */
    public static Applied apply(org.bukkit.plugin.Plugin plugin,
                                CurrencyManager currencies,
                                Player player,
                                Pool pool,
                                Prize prize,
                                boolean dropWhenFull) {
        PlaceholderContext ctx = PlaceholderContext.of(player).pool(pool).prize(prize);
        int given = 0;
        int dropped = 0;

        // 1) 物品奖励
        ItemStack item = prize.buildItem(ctx);
        if (item != null) {
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            if (!leftover.isEmpty()) {
                // 背包放不下：统一掉落在脚下，避免奖品丢失；管理员可关闭 drop-when-full 改为命令兜底。
                for (ItemStack drop : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), drop);
                    dropped += drop.getAmount();
                }
                if (!dropWhenFull) {
                    plugin.getLogger().info("玩家 " + player.getName() + " 背包已满，奖品已掉落在其脚下（drop-when-full=false）。");
                }
            }
            given++;
        }

        // 2) 货币奖励
        if (prize.currency() != null) {
            Currency currency = currencies.get(prize.currency().currencyId());
            if (currency == null || !currency.isAvailable()) {
                plugin.getLogger().warning("奖品 " + prize.id() + " 配置的货币 " + prize.currency().currencyId()
                        + " 当前不可用，玩家 " + player.getName() + " 未获得该奖励。");
            } else {
                double amount = prize.rollCurrency();
                if (!currency.deposit(player, amount)) {
                    plugin.getLogger().warning("向 " + player.getName() + " 发放货币 " + currency.id()
                            + " 失败，数量 " + amount + "，已尝试命令兜底。");
                    dispatch(plugin, player, "eco give " + player.getName() + " " + amount);
                }
            }
            given++;
        }

        // 3) 命令奖励
        for (String command : prize.commands()) {
            if (command == null || command.isBlank()) {
                continue;
            }
            dispatch(plugin, player, ctx.apply(command));
            given++;
        }
        return new Applied(given, dropped);
    }

    /**
     * 执行奖品命令，支持前缀：
     * <ul>
     *     <li>{@code player:} —— 以玩家身份执行；</li>
     *     <li>{@code console:} —— 以控制台身份执行（默认）；</li>
     *     <li>{@code op:} —— 临时提权后以玩家身份执行（需要服务器允许）。</li>
     * </ul>
     */
    private static void dispatch(org.bukkit.plugin.Plugin plugin, Player player, String command) {
        if (command == null || command.isBlank()) {
            return;
        }
        String raw = command.startsWith("/") ? command.substring(1) : command;
        String lower = raw.toLowerCase(Locale.ROOT);
        String body = raw;
        boolean asPlayer = false;
        if (lower.startsWith("player:")) {
            body = raw.substring("player:".length()).trim();
            asPlayer = true;
        } else if (lower.startsWith("console:")) {
            body = raw.substring("console:".length()).trim();
        } else if (lower.startsWith("op:")) {
            body = raw.substring("op:".length()).trim();
            asPlayer = true;
        }
        final String finalBody = body;
        final boolean finalAsPlayer = asPlayer;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (finalAsPlayer && player.isOnline()) {
                player.performCommand(finalBody);
            } else {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalBody);
            }
        });
    }

    /** 生成提示语用的奖品摘要，例如 “<gold>传说之剑<gray> ×1”。 */
    public static String summary(List<Prize> prizes, Map<String, Integer> counts) {
        List<String> parts = new ArrayList<>();
        for (Prize prize : prizes) {
            int count = counts.getOrDefault(prize.id(), 1);
            parts.add(prize.coloredName() + "<gray>×<white>" + count);
        }
        return String.join("<gray>, ", parts);
    }

    /**
     * 生成用于持久化的奖品摘要。
     * <p>
     * 流水记录会被再次用 MiniMessage 渲染，因此这里统一转换为 {@code §} 颜色代码，
     * 避免出现「&lt;green&gt;&lt;green&gt;铁块」这类重复标签。
     */
    public static String summaryForStorage(List<Prize> prizes, Map<String, Integer> counts) {
        List<String> parts = new ArrayList<>();
        for (Prize prize : prizes) {
            int count = counts.getOrDefault(prize.id(), 1);
            parts.add(prize.coloredName() + "<gray>×<white>" + count);
        }
        return cn.dsh.lottery.util.Text.legacyJoin(parts, "<gray>, ");
    }
}
