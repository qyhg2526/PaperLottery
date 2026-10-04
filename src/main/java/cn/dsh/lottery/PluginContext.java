package cn.dsh.lottery;

import cn.dsh.lottery.animation.AnimationRunner;
import cn.dsh.lottery.config.Messages;
import cn.dsh.lottery.config.Settings;
import cn.dsh.lottery.currency.CurrencyManager;
import cn.dsh.lottery.data.DataStore;
import cn.dsh.lottery.lottery.LotteryConfig;
import cn.dsh.lottery.lottery.LotteryService;
import cn.dsh.lottery.ui.UiManager;

/**
 * 插件上下文：集中持有各 Manager，避免各处重复注入。
 * <p>
 * 各 Manager 之间存在互相引用（抽奖服务需要动画引擎，动画引擎需要配置与数据），
 * 因此这里做成可变容器：先创建对象，再回填引用，避免构造顺序死锁。
 */
public final class PluginContext {

    private final PaperLotteryPlugin plugin;
    private final LotteryConfig config;
    private final DataStore data;

    private LotteryService service;
    private UiManager ui;
    private AnimationRunner animation;

    public PluginContext(PaperLotteryPlugin plugin, LotteryConfig config, DataStore data) {
        this.plugin = plugin;
        this.config = config;
        this.data = data;
    }

    public PaperLotteryPlugin plugin() {
        return plugin;
    }

    public LotteryConfig config() {
        return config;
    }

    public DataStore data() {
        return data;
    }

    public LotteryService service() {
        return service;
    }

    public UiManager ui() {
        return ui;
    }

    public AnimationRunner animation() {
        return animation;
    }

    public PluginContext service(LotteryService service) {
        this.service = service;
        return this;
    }

    public PluginContext ui(UiManager ui) {
        this.ui = ui;
        return this;
    }

    public PluginContext animation(AnimationRunner animation) {
        this.animation = animation;
        return this;
    }

    public Messages messages() {
        return config.messages();
    }

    public Settings settings() {
        return config.settings();
    }

    public CurrencyManager currencies() {
        return config.currencies();
    }
}
