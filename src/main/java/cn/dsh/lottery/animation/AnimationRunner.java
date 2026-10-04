package cn.dsh.lottery.animation;

import cn.dsh.lottery.PaperLotteryPlugin;
import cn.dsh.lottery.PluginContext;
import cn.dsh.lottery.config.AnimationSettings;
import cn.dsh.lottery.config.AnimationType;
import cn.dsh.lottery.config.Messages;
import cn.dsh.lottery.model.DrawOutcome;
import cn.dsh.lottery.model.Pool;
import cn.dsh.lottery.model.Prize;
import cn.dsh.lottery.util.ItemBuilder;
import cn.dsh.lottery.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 抽奖动画引擎。
 * <p>
 * 支持三种动画（见 {@link AnimationType}）：
 * <ul>
 *     <li><b>inventory</b> —— 箱子开箱式转盘：借用玩家快捷栏 9 个格子作为转盘，
 *         物品横向滚动并逐渐减速，最后指针定格在奖品上（CS:GO 开箱效果）；</li>
 *     <li><b>title</b> —— 屏幕中央标题快速翻滚奖品名，最终定格；</li>
 *     <li><b>actionbar</b> —— 物品栏上方文字滚动，开销最小。</li>
 * </ul>
 * <b>安全性</b>：转盘期间会临时清空并备份玩家快捷栏内容，动画结束、玩家退出、
 * 玩家手动关闭界面或插件卸载时都会原样恢复，因此不会吞物品。
 * 结算顺序为「先算结果 → 播动画 → 再发奖」，所以动画期间不会重复扣费或重复发奖。
 */
public final class AnimationRunner {

    private final PaperLotteryPlugin plugin;
    private final PluginContext ctx;
    private final Random random = new Random();

    /** 正在播放动画的玩家。 */
    private final Map<UUID, Active> active = new ConcurrentHashMap<>();

    public AnimationRunner(PaperLotteryPlugin plugin, PluginContext ctx) {
        this.plugin = plugin;
        this.ctx = ctx;
    }

    /** 一次动画会话。 */
    private static final class Active {
        final Player player;
        final Inventory view;
        final ItemStack[] backup;
        final List<Prize> sequence;
        final Pool pool;
        final DrawOutcome outcome;
        final Runnable onFinish;
        final AnimationSettings settings;
        final Material filler;
        final int reelSize;
        int pullIndex;
        int cursor;
        int ticks;
        int waits;
        long nextFrameAt;
        boolean closing;
        BukkitTask task;
        Active(Player player, Inventory view, ItemStack[] backup, List<Prize> sequence, Pool pool,
               DrawOutcome outcome, Runnable onFinish, AnimationSettings settings, Material filler) {
            this.player = player;
            this.view = view;
            this.backup = backup;
            this.sequence = sequence;
            this.pool = pool;
            this.outcome = outcome;
            this.onFinish = onFinish;
            this.settings = settings;
            this.filler = filler;
            this.reelSize = Math.max(9, view.getSize());
            this.cursor = settings.safeCursor();
        }
    }

    // ================================================================== 对外 API

    public boolean isAnimating(Player player) {
        return player != null && active.containsKey(player.getUniqueId());
    }

    /**
     * 播放抽奖动画，动画结束后回调 {@code onFinish}。
     *
     * @param outcome  抽奖结果（奖品已确定，尚未发放）
     * @param onFinish 动画结束（或动画不可用时立即）执行的回调
     */
    public void play(DrawOutcome outcome, Runnable onFinish) {
        AnimationSettings settings = ctx.settings().animation();
        Player player = outcome.player();
        int animated = settings.animatedPulls(outcome.amount());

        if (animated <= 0 || outcome.prizes().isEmpty() || !player.isOnline()) {
            onFinish.run();
            return;
        }
        if (active.containsKey(player.getUniqueId())) {
            // 同一玩家已有动画在播，直接结算，避免互相干扰
            onFinish.run();
            return;
        }

        List<Prize> sequence = buildSequence(outcome, animated);
        // 卡池定义从配置里取，outcome.pool() 只是卡池 ID 字符串
        Pool pool = ctx.config().pool(outcome.pool());
        if (pool == null) {
            onFinish.run();
            return;
        }
        try {
            switch (settings.type()) {
                case INVENTORY -> playInventory(outcome, pool, sequence, onFinish, settings);
                case TITLE -> playText(outcome, pool, sequence, onFinish, settings, true);
                case ACTIONBAR -> playText(outcome, pool, sequence, onFinish, settings, false);
                default -> onFinish.run();
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("播放抽奖动画失败，已直接结算：" + t);
            onFinish.run();
        }
    }

    /** 取消耗时较长的动画，直接进入结算（例如本批抽奖的后续部分）。 */
    public void skipToFinish(Player player) {
        Active state = active.get(player.getUniqueId());
        if (state != null) {
            finish(state);
        }
    }

    /** 玩家退出：恢复快捷栏并立即结束动画。 */
    public void handleQuit(Player player) {
        Active state = active.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        restore(state);
        if (state.task != null) {
            state.task.cancel();
        }
        active.remove(player.getUniqueId());
    }

    /** 点击/拖拽拦截：动画期间禁止操作转盘。 */
    public boolean isAnimatingView(Inventory top) {
        for (Active state : active.values()) {
            if (state.view.equals(top)) {
                return true;
            }
        }
        return false;
    }

    /** 玩家尝试关闭转盘界面：结束动画并结算，避免奖品丢失。 */
    public boolean handleClose(Player player, Inventory top) {
        Active state = active.get(player.getUniqueId());
        if (state == null || !state.view.equals(top) || state.closing) {
            return false;
        }
        finish(state);
        return true;
    }

    /** 插件卸载：恢复所有玩家的快捷栏。 */
    public void shutdown() {
        for (Active state : new ArrayList<>(active.values())) {
            if (state.task != null) {
                state.task.cancel();
            }
            restore(state);
            active.remove(state.player.getUniqueId());
        }
    }

    /**
     * 收敛转盘格子数：必须是 9 的倍数，且 9 ~ 54。
     * <p>
     * 原版自定义容器有硬性限制，超范围会让 {@code Bukkit.createInventory} 直接抛异常。
     */
    public static int clampReelSize(int configured) {
        if (configured <= 9) {
            return 9;
        }
        if (configured >= 54) {
            return 54;
        }
        // 向下取整到最近的 9 的倍数（例如 10 → 9，27 → 27）
        return Math.max(9, (configured / 9) * 9);
    }

    // ================================================================== 物品栏转盘

    private void playInventory(DrawOutcome outcome, Pool pool, List<Prize> sequence, Runnable onFinish, AnimationSettings settings) {
        Player player = outcome.player();
        // 原版自定义容器大小必须是 9 的倍数且不超过 54，这里做硬性收敛
        int size = clampReelSize(settings.reelSize());
        if (size != settings.reelSize()) {
            plugin.getLogger().warning("animation.reel-size=" + settings.reelSize()
                    + " 不合法（必须是 9 的倍数且不超过 54），本次按 " + size + " 处理。");
        }

        Inventory view = Bukkit.createInventory(null, size, Text.mm(
                ctx.messages().raw("animation.title", "<gold>✦ 抽取中… ✦")));
        // 备份并清空玩家快捷栏（前 9 格），转盘就画在这里，不占用背包其它位置
        ItemStack[] backup = new ItemStack[9];
        for (int slot = 0; slot < 9; slot++) {
            backup[slot] = player.getInventory().getItem(slot);
            player.getInventory().setItem(slot, null);
        }
        player.updateInventory();
        if (ctx.settings().debug()) {
            plugin.getLogger().info("[DEBUG] 转盘备份 " + player.getName() + " 快捷栏：" + describe(backup));
        }
        player.openInventory(view);

        Material filler = resolveFiller();
        Active state = new Active(player, view, backup, sequence, pool, outcome, onFinish, settings, filler);
        active.put(player.getUniqueId(), state);
        // 起始帧：指针位直接显示本轮结果，随后指针向右滚出、绕完一圈再回到指针位定格
        renderFrame(state, state.cursor);
        state.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(state), 1L, 1L);
    }

    private void tick(Active state) {
        if (state.closing) {
            return;
        }
        Player player = state.player;
        if (!player.isOnline()) {
            handleQuit(player);
            return;
        }
        if (System.currentTimeMillis() < state.nextFrameAt) {
            return;
        }

        int target = state.settings.safeCursor();
        if (state.cursor == target && state.ticks > target) {
            // 指针已经绕完一圈回到结果格：停留若干 tick 后进入下一次抽奖或收尾
            state.waits++;
            if (state.waits <= state.settings.settleAfter()) {
                return;
            }
            state.waits = 0;
            state.pullIndex++;
            playWinSound(state);
            if (state.pullIndex >= state.sequence.size()) {
                finish(state);
                return;
            }
            // 重新加速，开始下一次滚动
            state.ticks = 0;
            state.cursor = state.settings.safeCursor();
            state.nextFrameAt = System.currentTimeMillis() + state.settings.minSlotTickMs();
            renderFrame(state, state.cursor);
            return;
        }

        // 指针必须在 0..reelSize-1 之间循环前进：
        // 只有回到目标格时才会触发上面的定格分支，否则动画会一直转下去。
        state.cursor = (state.cursor + 1) % state.reelSize;
        state.ticks++;
        state.nextFrameAt = System.currentTimeMillis() + slotDelay(state);
        renderFrame(state, state.cursor);
        playTickSound(state);
    }

    /**
     * 每格耗时（返回「滚到这一格之后、再等多久滚下一格」的毫秒数）。
     * <p>
     * 前段匀速快滚；最后若干格按 {@code slot-ticks} 曲线逐级减速，
     * 制造「越转越慢、最后咔哒停下」的手感。
     */
    private long slotDelay(Active state) {
        AnimationSettings settings = state.settings;
        int[] pattern = settings.slotTicks().stream().mapToInt(Integer::intValue).toArray();
        return ReelMath.slotDelay(state.ticks, settings.safeCursor(), pattern,
                settings.rollTickMs(), settings.minSlotTickMs());
    }

    /** 渲染一帧：把算法算出的窗口映射成实际物品。 */
    private void renderFrame(Active state, int cursor) {
        int[] indices = ReelMath.window(state.ticks, cursor, state.reelSize);
        for (int slot = 0; slot < state.view.getSize() && slot < indices.length; slot++) {
            state.view.setItem(slot, indices[slot] == ReelMath.EMPTY
                    ? fillerItem(state, slot)
                    : itemFor(state, indices[slot]));
        }
        state.player.updateInventory();
    }

    /**
     * 第 {@code index} 格展示的物品。
     * <p>
     * 当该格序号对转盘长度取模等于指针位时，显示本轮的真实结果；其余格子按卡池权重随机，
     * 因此玩家看到的转盘内容与真实概率分布一致。
     */
    private ItemStack itemFor(Active state, int index) {
        int stepIndex = Math.floorMod(index, state.reelSize);
        Prize prize;
        if (stepIndex == state.settings.safeCursor()) {
            prize = state.sequence.get(Math.min(state.pullIndex, state.sequence.size() - 1));
        } else {
            prize = state.pool.prizes().isEmpty()
                    ? null
                    : state.pool.prizes().get(random.nextInt(state.pool.prizes().size()));
        }
        if (prize == null) {
            return fillerItem(state, index);
        }
        return previewItem(state, prize);
    }

    private ItemStack fillerItem(Active state, int index) {
        ItemStack stack = new ItemStack(state.filler);
        var meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.mm(state.settings.fillerName(index)));
            if (!state.settings.fillerLore().isEmpty()) {
                meta.lore(Text.mmList(state.settings.fillerLore(), null));
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** 生成用于展示的奖品图标（不随机数量，避免每次刷新跳变）。 */
    private ItemStack previewItem(Active state, Prize prize) {
        String name = state.settings.showNames()
                ? Messages.rarityColor(prize.rarity()) + prize.displayName()
                : null;
        Material material = fillerOf(prize, state.filler);
        ItemStack stack = ItemBuilder.build(material, 1, name, List.of(), List.of(), null, false, false, null);
        var meta = stack.getItemMeta();
        if (meta != null) {
            // 用品质颜色作为物品名，并附加品质提示，便于玩家一眼分辨
            meta.displayName(Text.mm(Messages.rarityColor(prize.rarity()) + prize.displayName()));
            meta.lore(List.of(Text.mm("<dark_gray>" + Messages.rarityName(prize.rarity()))));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** 奖品如果没有物品形态（纯货币/命令），用配置的填充材质代替。 */
    private Material fillerOf(Prize prize, Material fallback) {
        if (prize.item() != null && prize.item().material() != null && !prize.item().material().isAir()) {
            return prize.item().material();
        }
        return switch (prize.rarity()) {
            case "legendary" -> Material.NETHER_STAR;
            case "epic" -> Material.AMETHYST_SHARD;
            case "rare" -> Material.DIAMOND;
            case "uncommon" -> Material.EMERALD;
            default -> fallback;
        };
    }

    private Material resolveFiller() {
        Material material = Material.matchMaterial(
                ctx.messages().raw("animation.filler-material", "GRAY_STAINED_GLASS_PANE"));
        return material == null || material.isAir() ? Material.GRAY_STAINED_GLASS_PANE : material;
    }

    // ================================================================== 标题 / 动作栏

    private void playText(DrawOutcome outcome, Pool pool, List<Prize> sequence, Runnable onFinish,
                          AnimationSettings settings, boolean title) {
        Player player = outcome.player();
        Active state = new Active(player, Bukkit.createInventory(null, 9), new ItemStack[9], sequence,
                pool, outcome, onFinish, settings, Material.AIR);
        active.put(player.getUniqueId(), state);

        int interval = Math.max(1, settings.durationTicks() / Math.max(1, sequence.size() * 12));
        state.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (state.closing) {
                return;
            }
            if (!player.isOnline()) {
                handleQuit(player);
                return;
            }
            Prize current = sequence.get(Math.min(state.pullIndex, sequence.size() - 1));
            Prize roll = state.pool.prizes().isEmpty()
                    ? current
                    : state.pool.prizes().get(random.nextInt(state.pool.prizes().size()));
            state.ticks++;
            boolean settling = state.ticks >= settings.durationTicks();
            Prize shown = settling ? current : roll;

            Component main = Text.mm(Messages.rarityColor(shown.rarity()) + shown.displayName());
            Component sub = Text.mm(ctx.messages().raw("animation.subtitle", "<gray>本轮：<white>%pool_name%")
                    .replace("%pool_name%", Text.plain(state.pool.displayName())));
            if (title) {
                player.showTitle(Title.title(main, sub,
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(700), Duration.ofMillis(120))));
            } else {
                player.sendActionBar(Text.mm(ctx.messages().raw("animation.actionbar", "<gray>抽取中… %prize_name%")
                        .replace("%prize_name%", Messages.rarityColor(shown.rarity()) + shown.displayName())));
            }
            playTickSound(state);

            if (settling) {
                state.pullIndex++;
                playWinSound(state);
                if (state.pullIndex >= sequence.size()) {
                    finish(state);
                    return;
                }
                state.ticks = 0;
            }
        }, interval, interval);
    }

    // ================================================================== 收尾

    /** 结束动画：恢复快捷栏 → 关闭界面 → 结算奖励。 */
    private void finish(Active state) {
        if (state.closing) {
            return;
        }
        state.closing = true;
        if (state.task != null) {
            state.task.cancel();
        }
        Player player = state.player;
        if (player.isOnline()) {
            restore(state);
            player.closeInventory();
        } else {
            restore(state);
        }
        active.remove(player.getUniqueId());
        try {
            state.onFinish.run();
        } catch (Throwable t) {
            plugin.getLogger().severe("动画结束回调执行失败：" + t);
        }
    }

    /** 把备份的快捷栏物品放回原位。 */
    private void restore(Active state) {
        Player player = state.player;
        if (player == null || !player.isOnline()) {
            return;
        }
        try {
            for (int slot = 0; slot < state.backup.length && slot < 9; slot++) {
                player.getInventory().setItem(slot, state.backup[slot]);
            }
            player.updateInventory();
            if (ctx.settings().debug()) {
                ItemStack[] now = new ItemStack[9];
                for (int slot = 0; slot < 9; slot++) {
                    now[slot] = player.getInventory().getItem(slot);
                }
                plugin.getLogger().info("[DEBUG] 转盘恢复 " + player.getName() + " 快捷栏：备份="
                        + describe(state.backup) + " 现状=" + describe(now));
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("恢复玩家快捷栏失败（" + player.getName() + "）：" + t.getMessage());
        }
    }

    /** 调试用：把物品数组渲染成可读字符串。 */
    private static String describe(ItemStack[] items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            ItemStack item = items[i];
            sb.append(item == null ? "-" : item.getType().name() + "x" + item.getAmount());
        }
        return sb.append(']').toString();
    }

    private void playTickSound(Active state) {
        if (!state.settings.soundEnabled() || !state.player.isOnline()) {
            return;
        }
        try {
            Sound sound = resolveSound(state.settings.tickSound(), Sound.BLOCK_NOTE_BLOCK_HAT);
            state.player.playSound(state.player.getLocation(), sound, 0.6F, 1.4F);
        } catch (Throwable ignored) {
            // 音效失败不影响动画
        }
    }

    private void playWinSound(Active state) {
        if (!state.settings.soundEnabled() || !state.player.isOnline()) {
            return;
        }
        try {
            Sound sound = resolveSound(state.settings.winSound(), Sound.ENTITY_PLAYER_LEVELUP);
            state.player.playSound(state.player.getLocation(), sound, 1.0F, 1.2F);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 支持 {@code block.note_block.hat} 这类命名空间写法与 {@code BLOCK_NOTE_BLOCK_HAT} 枚举写法。 */
    public static Sound resolveSound(String raw, Sound fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            String value = raw.trim();
            if (value.contains(":")) {
                var key = org.bukkit.NamespacedKey.fromString(value.toLowerCase(java.util.Locale.ROOT));
                Sound sound = key == null ? null : org.bukkit.Registry.SOUND_EVENT.get(key);
                if (sound != null) {
                    return sound;
                }
            }
            value = value.toUpperCase(java.util.Locale.ROOT).replace('.', '_').replace(':', '_');
            @SuppressWarnings("deprecation")
            Sound byName = Sound.valueOf(value);
            return byName;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    /** 组装本次要播放的奖品序列（顺序与抽奖结果一致）。 */
    private List<Prize> buildSequence(DrawOutcome outcome, int animated) {
        List<Prize> sequence = new ArrayList<>();
        for (Prize prize : outcome.prizes()) {
            int count = outcome.counts().getOrDefault(prize.id(), 1);
            for (int i = 0; i < count && sequence.size() < animated; i++) {
                sequence.add(prize);
            }
            if (sequence.size() >= animated) {
                break;
            }
        }
        if (sequence.isEmpty()) {
            sequence.addAll(outcome.prizes());
        }
        return sequence;
    }

    /** 转盘窗口快照，供调试/测试使用。 */
    public Map<String, Object> debugState(Player player) {
        Active state = active.get(player.getUniqueId());
        if (state == null) {
            return Map.of("animating", false);
        }
        Map<String, Object> map = new HashMap<>();
        map.put("animating", true);
        map.put("type", state.settings.type().name());
        map.put("cursor", state.cursor);
        map.put("ticks", state.ticks);
        map.put("pullIndex", state.pullIndex);
        map.put("sequence", state.sequence.stream().map(Prize::id).toList());
        map.put("backup", Arrays.toString(Arrays.stream(state.backup).map(item ->
                item == null ? "-" : item.getType().name() + "x" + item.getAmount()).toArray()));
        return map;
    }
}
