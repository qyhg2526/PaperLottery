package cn.dsh.lottery.event;

import cn.dsh.lottery.model.DrawOutcome;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * 抽奖完成事件：奖励已发放，可在此做统计、成就、额外播报等扩展。
 */
public class LotteryDrawEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final DrawOutcome result;

    public LotteryDrawEvent(DrawOutcome result) {
        this.result = result;
    }

    public DrawOutcome getResult() {
        return result;
    }

    public Player getPlayer() {
        return result.player();
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
