package cn.dsh.lottery.config;

import java.util.Locale;

/**
 * 抽奖动画类型。
 * <ul>
 *     <li>{@link #NONE} —— 不播放动画，直接出结果；</li>
 *     <li>{@link #INVENTORY} —— 箱子开箱式转盘（GUI 横向滚动，指针定格）；</li>
 *     <li>{@link #TITLE} —— 屏幕中央标题翻滚；</li>
 *     <li>{@link #ACTIONBAR} —— 物品栏上方文字滚动。</li>
 * </ul>
 */
public enum AnimationType {

    NONE("无"),
    INVENTORY("GUI 开箱转盘"),
    TITLE("标题翻滚"),
    ACTIONBAR("动作栏滚动");

    private final String label;

    AnimationType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 宽松解析：未知值回退到 {@link #INVENTORY}。 */
    public static AnimationType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return INVENTORY;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (value) {
            case "none", "off", "disable", "disabled", "false" -> NONE;
            case "title", "titles" -> TITLE;
            case "actionbar", "action_bar", "bar", "hotbar" -> ACTIONBAR;
            case "inventory", "gui", "chest", "reel", "csgo", "case", "roulette" -> INVENTORY;
            default -> INVENTORY;
        };
    }
}
