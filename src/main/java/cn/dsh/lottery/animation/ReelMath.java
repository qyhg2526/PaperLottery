package cn.dsh.lottery.animation;

/**
 * 转盘滚动算法（纯函数，便于单独验证）。
 * <p>
 * 约定：
 * <ul>
 *     <li>{@code ticks} —— 从本轮开始已经滚过的格数；</li>
 *     <li>{@code cursor} —— 当前指针所在的窗口下标；</li>
 *     <li>{@code reelSize} —— 窗口（可见格子）数量；</li>
 *     <li>{@code target} —— 抽奖开始时指针所在的下标，也是结果定格的位置。</li>
 * </ul>
 * 窗口下标 {@code i} 在时刻 {@code ticks} 显示的格子序号为 {@code ticks - (cursor - i)}，
 * 因此在 {@code ticks == target}（即指针回到目标格）时，
 * 指针处的格子序号正好是 {@code target}，而 {@code target} 取模 {@code reelSize} 落在指针位上，
 * 也就是本轮的结果。
 */
public final class ReelMath {

    /** 表示该格还没有内容（用填充物渲染）。 */
    public static final int EMPTY = -1;

    private ReelMath() {
    }

    /**
     * 计算某一时刻窗口内每格应显示的「格子序号」。
     *
     * @return 长度为 {@code reelSize} 的数组，元素为格子序号，{@link #EMPTY} 表示尚未滚入
     */
    public static int[] window(int ticks, int cursor, int reelSize) {
        int[] indices = new int[reelSize];
        for (int i = 0; i < reelSize; i++) {
            int index = ticks - (cursor - i);
            indices[i] = index < 0 ? EMPTY : index;
        }
        return indices;
    }

    /** 本轮结果在窗口中的下标（即配置的 {@code cursor-slot}）。 */
    public static int winningSlot(int ticks, int reelSize) {
        return Math.floorMod(ticks, reelSize);
    }

    /**
     * 每格耗时（毫秒）。
     *
     * @param ticks      已经滚过的格数
     * @param target     目标格下标
     * @param pattern    减速曲线（最后几格的相对耗时）
     * @param rollTickMs 匀速阶段的每格耗时
     * @param minSlotMs  最慢一格的耗时下限
     */
    public static long slotDelay(int ticks, int target, int[] pattern, int rollTickMs, int minSlotMs) {
        if (pattern == null || pattern.length == 0) {
            return rollTickMs;
        }
        int remaining = Math.max(1, target - ticks);
        int index = pattern.length - remaining;
        if (index >= pattern.length) {
            return rollTickMs;
        }
        int curve = pattern[Math.max(0, index)];
        return Math.max(minSlotMs, (long) curve * 12L);
    }

    /**
     * 判断本轮是否已经结束滚动：指针回到目标格，且已经完整滚过一圈以上，
     * 保证指针处显示的确实是结果而不是随机物品。
     */
    public static boolean landed(int ticks, int target) {
        return ticks >= target && ticks > 0;
    }
}
