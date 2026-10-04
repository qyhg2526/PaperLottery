package cn.dsh.lottery.event;

import cn.dsh.lottery.model.Pool;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * 抽奖前置事件：在其他插件的抽奖逻辑或加成生效前触发，可被取消或修改次数。
 * <p>
 * 使用示例：
 * <pre>{@code
 * @EventHandler(ignoreCancelled = true)
 * public void onPreDraw(LotteryPreDrawEvent event) {
 *     if (event.getPool().id().equals("normal")) {
 *         event.setAmount(event.getAmount() * 2); // 双倍活动
 *     }
 * }
 * }</pre>
 */
public class LotteryPreDrawEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Pool pool;
    private int amount;
    private boolean cancelled;

    public LotteryPreDrawEvent(Player player, Pool pool, int amount) {
        this.player = player;
        this.pool = pool;
        this.amount = amount;
    }

    public Player getPlayer() {
        return player;
    }

    public Pool getPool() {
        return pool;
    }

    public int getAmount() {
        return amount;
    }

    /** 修改本次抽奖次数（必须 ≥ 1）。 */
    public void setAmount(int amount) {
        this.amount = Math.max(1, amount);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
