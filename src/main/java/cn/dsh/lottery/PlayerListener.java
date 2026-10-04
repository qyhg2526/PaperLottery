package cn.dsh.lottery;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 玩家监听：维护玩家名缓存，并在退出时刷新数据。
 */
public final class PlayerListener implements Listener {

    private final PaperLotteryPlugin plugin;

    public PlayerListener(PaperLotteryPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        PluginContext ctx = plugin.ctx();
        if (ctx == null) {
            return;
        }
        ctx.data().get(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        ctx.data().markDirty();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        PluginContext ctx = plugin.ctx();
        if (ctx == null) {
            return;
        }
        ctx.data().get(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        if (ctx.service() != null) {
            ctx.service().clearPending(event.getPlayer());
        }
        ctx.data().markDirty();
    }
}
