package cn.dsh.lottery.ui;

import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import net.kyori.adventure.text.event.ClickCallback;

import java.time.Duration;

/**
 * Dialog 动作封装：统一自定义点击回调的有效期与可用次数。
 */
public final class DialogActions {

    /** 默认回调有效期：5 分钟，足够玩家在对话框内连续操作。 */
    private static final Duration LIFETIME = Duration.ofMinutes(5);

    private DialogActions() {
    }

    /**
     * 创建自定义点击动作。
     *
     * @param callback 回调（参数为对话框响应数据与点击者）
     */
    public static DialogAction customClick(DialogActionCallback callback) {
        return DialogAction.customClick(callback, ClickCallback.Options.builder()
                .uses(ClickCallback.UNLIMITED_USES)
                .lifetime(LIFETIME)
                .build());
    }
}
