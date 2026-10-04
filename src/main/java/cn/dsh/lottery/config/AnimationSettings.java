package cn.dsh.lottery.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 抽奖动画设置（config.yml 的 {@code animation} 节点）。
 *
 * @param enabled        是否启用动画（关闭后抽奖立即出结果）
 * @param type           动画类型：{@code none} / {@code inventory} / {@code title} / {@code actionbar}
 * @param durationTicks  单次抽奖动画时长（tick，20 tick = 1 秒）
 * @param reelSize       转盘长度（可见格子数），仅 inventory 使用
 * @param slotTicks      复用上次结果时的格子节奏，见 {@link #timingPattern()}
 * @param cursorSlot     指针所在格子（结果落在该格）
 * @param maxAnimated    单次最多为几次抽奖播放动画，超出部分直接结算
 * @param settleAfter    全部抽奖结束后额外停留的 tick（让指针停留一会儿）
 * @param rollTickMs     滚动阶段每格耗时（毫秒）
 * @param minSlotTickMs  收尾阶段每格最慢耗时（毫秒）
 * @param tickSound      每格播放的音效名（留空则不播放）
 * @param winSound       结果定格时播放的音效名
 * @param showNames      转盘上是否显示奖品名（false 时仅用材质图标）
 * @param fillerName     转盘空位填充物展示名
 * @param fillerLore     转盘空位填充物描述
 */
public record AnimationSettings(
        boolean enabled,
        AnimationType type,
        int durationTicks,
        int reelSize,
        List<Integer> slotTicks,
        int cursorSlot,
        int maxAnimated,
        int settleAfter,
        int rollTickMs,
        int minSlotTickMs,
        String tickSound,
        String winSound,
        boolean showNames,
        List<String> fillerName,
        List<String> fillerLore
) {

    /** 转盘空位填充物的默认展示名（按顺序循环使用）。 */
    private static final List<String> DEFAULT_FILLERS = List.of(
            "<dark_gray>▪", "<dark_gray>▫", "<gray>•", "<dark_gray>◆"
    );

    public static AnimationSettings load(ConfigurationSection section) {
        if (section == null) {
            return defaults();
        }
        AnimationType type = AnimationType.parse(section.getString("type", "inventory"));
        List<Integer> slotTicks = section.getIntegerList("slot-ticks");
        if (slotTicks.isEmpty()) {
            slotTicks = List.of(1, 2, 3, 5, 8, 13);
        }
        List<String> interpolate = section.getStringList("filler-name");
        if (interpolate.isEmpty()) {
            interpolate = DEFAULT_FILLERS;
        }
        return new AnimationSettings(
                section.getBoolean("enabled", true),
                type,
                Math.max(5, section.getInt("duration-ticks", 48)),
                Math.max(3, Math.min(9, section.getInt("reel-size", 9))),
                slotTicks.stream().map(v -> Math.max(1, v)).toList(),
                section.getInt("cursor-slot", 4),
                Math.max(1, section.getInt("max-animated-pulls", 10)),
                Math.max(0, section.getInt("settle-ticks", 15)),
                Math.max(10, section.getInt("roll-tick-ms", 55)),
                Math.max(20, section.getInt("min-slot-tick-ms", 90)),
                section.getString("sounds.tick", "block.note_block.hat"),
                section.getString("sounds.win", "entity.player.levelup"),
                section.getBoolean("show-names", true),
                List.copyOf(interpolate),
                List.copyOf(section.getStringList("filler-lore"))
        );
    }

    public static AnimationSettings defaults() {
        return new AnimationSettings(true, AnimationType.INVENTORY, 48, 9, List.of(1, 2, 3, 5, 8, 13), 4,
                10, 15, 55, 90, "block.note_block.hat", "entity.player.levelup", true,
                DEFAULT_FILLERS, List.of());
    }

    /** 动画是否真的会播放。 */
    public boolean active() {
        return enabled && type != AnimationType.NONE;
    }

    /** 需要播放动画的抽奖次数（0 表示不播放）。 */
    public int animatedPulls(int amount) {
        if (!active()) {
            return 0;
        }
        return Math.min(Math.max(1, amount), maxAnimated);
    }

    /** 指针格子的合法值（0 ~ reelSize-1）。 */
    public int safeCursor() {
        return Math.max(0, Math.min(reelSize - 1, cursorSlot));
    }

    public boolean soundEnabled() {
        return tickSound != null && !tickSound.isBlank() && !"none".equalsIgnoreCase(tickSound);
    }

    /** 空位填充物的材质（随索引循环）。 */
    public String fillerName(int index) {
        return fillerName.get(Math.floorMod(index, fillerName.size()));
    }

    /** 调试输出用。 */
    public Map<String, Object> describe() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", enabled);
        map.put("type", type.name().toLowerCase(Locale.ROOT));
        map.put("duration-ticks", durationTicks);
        map.put("reel-size", reelSize);
        map.put("cursor-slot", safeCursor());
        return map;
    }
}
