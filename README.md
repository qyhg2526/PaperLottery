# PaperLottery

> 当前版本 **1.0.1** ｜ 基于 **Paper 26.2 Dialog API** 的 Minecraft 抽奖插件，接入 **Vault** 经济系统并支持 **多货币**。

PaperLottery 使用 Paper 原生对话框（Dialog）作为全部菜单界面，不占用任何背包格子，
支持权重概率、连抽保底、限量奖品、活动加成、全服播报与 PlaceholderAPI 变量。

---

## 更新日志

### 1.0.1 —— 保底系统修复（重要）

修复了 1.0.0 中保底系统的三个缺陷，建议所有服务器升级。

| 问题 | 表现 | 修复 |
| --- | --- | --- |
| **计数被污染** | 每次中奖都会按「奖品品质」额外自增计数器，导致没有保底规则的品质也产生计数（实测 300 抽后 `{common=178, uncommon=90, rare=20, epic=26, legendary=79}`），界面保底进度与实际抽数对不上 | 移除多余自增；保底计数只由 `PityEngine` 统一维护 |
| **计数越过阈值** | 当某品质奖品全部达到限额时，强制保底落空但计数继续增长，玩家看到「超过保底却不出货」且无法恢复 | 计数封顶在阈值；真的无法兑现时每 60 秒最多一次输出可诊断告警 |
| **高品质保底被抢占** | 多条规则同时就绪时，永远兑现 YAML 里写在前面的低品质规则，高品质保底轮不到 | 改为兑现**品质最高**的就绪规则，与书写顺序无关 |

同时新增：

* 界面保底进度统一从 `LotteryService.pityProgress()` 取快照，显示与判定共用同一份数据；
* 进度条旁新增「【已就绪】」标记，就绪时提示「下一次抽取必得该品质或更高」；
* **旧数据自动清理**：加载 `playerdata.yml` 时删除不属于任何保底规则的计数条目并打印日志，
  线上被污染的存档无需手动处理（有效计数原样保留）。

> **升级提示**：如果服务器上已有人被旧版本卡在「计数 30/30 却一直不出货」，
> 升级后**下一次抽奖就会立刻兑现保底**，这是预期行为。

验证：在真实 Paper 26.2 服务端对产品代码跑保底不变量测试，**10010 项断言全部通过**——
计数不超限、就绪必兑现（73/73）、高品质优先、计数与真实间隔偏差为 0、无幽灵计数。

### 1.0.0

首个版本：Dialog API 菜单、多货币（Vault / 多货币账户 / 经验 / 物品）、
权重概率、保底、限额、开箱转盘动画、`rewards.yml` 独立奖励配置。

---

## 目录

- [更新日志](#更新日志)
- [核心特性](#核心特性)
- [环境要求](#环境要求)
- [安装步骤](#安装步骤)
- [构建插件](#构建插件)
- [多货币系统](#多货币系统)
- [Vault 接入说明](#vault-接入说明)
- [Dialog 界面说明](#dialog-界面说明)
- [配置文件详解](#配置文件详解)
- [命令与权限](#命令与权限)
- [概率与保底算法](#概率与保底算法)
- [数据存储](#数据存储)
- [开发者 API](#开发者-api)
- [常见问题](#常见问题)

---

## 核心特性

| 能力 | 说明 |
| --- | --- |
| **Dialog API 菜单** | 主界面、抽奖结果、概率公示、保底进度、抽奖记录、二次确认、管理面板，全部为原生对话框 |
| **抽奖动画** | 箱子开箱式转盘（CS:GO 风格）/ 标题翻滚 / 动作栏滚动，可关闭；转盘借用快捷栏并保证零物品丢失 |
| **多货币** | 同一服务器内可同时存在金币（Vault）、点券（Vault 多货币账户）、经验等级、钻石（物品）等货币，每个卡池独立指定 |
| **Vault 兼容** | 自动挂接 Vault `Economy` 服务，兼容 EssentialsX、CMI、XConomy、CraftConomy、GemsEconomy、PlayerPoints 等 |
| **权重概率** | 权重轮盘算法，支持权限倍率、卡池倍率、周末与时段活动倍率 |
| **保底机制** | 任意品质、任意阈值、多条规则并存，进度实时可视化（进度条） |
| **奖品类型** | 物品（含附魔/耐久/发光/自定义模型）、货币、命令（控制台/玩家身份） |
| **奖励单独成文件** | 卡池与奖品集中在 `rewards.yml`，改奖励不用碰主配置，`/lottery reload` 即时生效 |
| **限额** | 全服限量、个人限量、每日限量，达到上限自动移出轮盘并重新归一化概率 |
| **文案** | MiniMessage + 旧版 `&` 颜色代码 + 内置占位符 + PlaceholderAPI 变量 |
| **事件** | `LotteryPreDrawEvent`（可取消/改次数）、`LotteryDrawEvent`（结算后） |
| **零硬依赖** | Vault 与 PlaceholderAPI 均为软依赖（通过反射/服务注册探测），未安装也能启动 |

---

## 环境要求

| 项目 | 要求 |
| --- | --- |
| 服务端 | Paper **26.2**（Dialog API 需要 Paper 1.21.6+；本插件按 26.2 的 API 编译） |
| Java | **Java 25**（Paper 26.2 的 API 以 Java 25 字节码发布） |
| 可选插件 | Vault + 任意经济插件（用于金币/点券货币） |
| 可选插件 | PlaceholderAPI（用于 `%变量%` 条件与文案） |

---

## 安装步骤

1. 将 `PaperLottery-1.0.1.jar` 放入服务端 `plugins/` 目录。
2. （可选）安装 `Vault.jar` 与一个经济插件，例如 EssentialsX。
3. 启动服务器，插件会生成 `plugins/PaperLottery/config.yml`。
4. 编辑 `config.yml` 配置货币、卡池、奖品与文案。
5. 执行 `/lottery reload` 或重启服务器。
6. 玩家执行 `/lottery` 即可打开对话框界面。

---

## 构建插件

环境要求：**JDK 25**。仓库已自带全部依赖 jar（`lib/`），因此构建**无需联网**。

### 方式一：一键脚本（推荐）

```powershell
# Windows PowerShell
./build.ps1
```

```bash
# Linux / macOS
pwsh -File ./build.ps1
```

产物：`build/libs/PaperLottery-1.0.1.jar`

> **为什么脚本用 ECJ 而不是 javac？**
> Paper 26.2 的 `paper-api` jar 中，部分方法同时带有
> `RuntimeInvisibleAnnotations` 与 `RuntimeInvisibleTypeAnnotations` 两份相同的 `@NotNull`，
> javac 会报错「无法将类型批注 @NotNull 附加到 ...」。
> Eclipse 编译器（ECJ）可以正常处理该 jar，因此脚本直接调用 `lib/ecj-*.jar` 编译，
> 无需联网、无需 Gradle 守护进程。

### 方式二：Gradle

```bash
./gradlew build       # 首次运行会下载 Gradle 发行包
```

> ⚠️ Gradle 走的是 javac，在本项目的 `paper-api` 版本上可能触发上述类型批注报错。
> 如果遇到，请改用 `build.ps1`。

### 方式三：GitHub Actions

推送到 `main` 分支或打 `v*` 标签时会自动编译并上传产物（见 `.github/workflows/build.yml`），
在 Actions 页面的 Artifacts 中即可下载 jar。

### 依赖清单

```
lib/paper-api-26.2.jar            # Paper 26.2 API（服务端提供，compileOnly）
lib/VaultAPI-1.7.1.jar            # Vault 经济接口（可选）
lib/adventure-*.jar               # Adventure 5.2.0（Paper 已内置）
lib/bungeecord-chat-*.jar         # Adventure 的 BungeeCord 兼容层（编译期需要）
lib/annotations-*.jar             # JetBrains 注解
lib/ecj-*.jar                     # Eclipse 编译器（仅构建用）
```

### 目录结构

```
src/main/java/cn/dsh/lottery/
├── PaperLotteryPlugin.java       插件入口
├── LotteryCommand.java           /lottery 命令与 Tab 补全
├── AnimationListener.java        动画期间的事件守卫
├── PlayerListener.java           玩家进出服数据维护
├── PluginContext.java            各 Manager 的持有者
├── animation/                    开箱转盘动画（AnimationRunner / ReelMath）
├── config/                       配置模型（Settings / AnimationSettings / Rarity / Messages …）
├── currency/                     多货币抽象与实现（Vault / 多货币 / 经验 / 物品）
├── data/                         玩家数据持久化
├── event/                        对外 API 事件
├── lottery/                      抽奖核心（LotteryService / PityEngine / LotteryConfig / RewardApplier）
├── model/                        Pool / Prize / DrawOutcome 等模型
├── ui/                           Dialog API 界面（DialogManager / DialogUtil / DialogActions）
└── util/                         文本、物品、占位符、PlaceholderAPI 桥接

src/main/resources/
├── plugin.yml                    插件描述
├── config.yml                    全局设置 + 品质 + 货币 + 动画 + 文案
└── rewards.yml                   卡池与奖品（奖励单独成文件）
```

---

## 多货币系统

所有抽奖消耗都通过统一的 `Currency` 接口完成，因此插件天然支持多货币。
在 `config.yml` 的 `currencies` 节点中声明货币，然后在卡池里用 `currency: <货币ID>` 引用。

### 内置货币类型

| type | 说明 | 关键字段 | 是否需要 Vault |
| --- | --- | --- | --- |
| `vault` | Vault 主经济（默认账户） | — | 是 |
| `vault-multi` | Vault 多货币账户 | `account`（如 `points`、`gems`） | 是 |
| `xp` | 原版经验 | `levels: true` 按等级计费，`false` 按经验点 | 否 |
| `item` | 原版物品 | `material`（如 `DIAMOND`）、`ignore-meta` | 否 |

### 配置示例

```yaml
currencies:
  # 金币：Vault 主经济
  vault:
    type: vault
    display-name: "<gold>金币"
    unit: "金币"

  # 经验等级：无需任何经济插件
  xp:
    type: xp
    display-name: "<green>经验等级"
    levels: true
    unit: "级"

  # 点券：Vault 多货币账户（PlayerPoints / GemsEconomy 等）
  points:
    type: vault-multi
    account: points
    display-name: "<aqua>点券"
    unit: "点"

  # 钻石：物品货币
  diamond:
    type: item
    material: DIAMOND
    display-name: "<aqua>钻石"
    unit: "颗"
    ignore-meta: true
```

卡池中引用：

```yaml
pools:
  normal:
    currency: vault      # 用金币抽
  premium:
    currency: points     # 用点券抽
  diamond:
    currency: diamond    # 用钻石抽
```

### 混合货币与货币奖励

奖品本身也可以发放任意货币（可以与消耗货币不同）：

```yaml
prizes:
  coins_small:
    currency:
      id: vault          # 发金币
      min: 200
      max: 800
  xp_levels:
    currency:
      id: xp             # 发经验等级
      min: 5
      max: 15
```

---

## Vault 接入说明

插件在启动与重载时会执行以下流程：

1. 检查 `Vault` 插件是否已启用；
2. 通过 `ServicesManager` 获取 `net.milkbowl.vault.economy.Economy` 服务提供者；
3. 校验经济插件是否处于启用状态；
4. 将 `type: vault` 的货币包装为 `VaultCurrency`，`type: vault-multi` 包装为 `VaultMultiCurrency`；
5. 若 Vault 或经济插件缺失，会在控制台给出明确提示，并跳过这些货币（其他货币照常工作）。

所有 Vault 调用都包裹了异常保护：经济插件内部报错时不会导致抽奖流程崩溃，
而是退化为「余额不足 / 扣款失败」的提示。

> 未安装 Vault 时，可以先把卡池货币改成 `xp` 或 `item`，功能完全可用。

---

## Dialog 界面说明

| 界面 | 打开方式 | 内容 |
| --- | --- | --- |
| **主界面** | `/lottery`、`/lottery open [卡池]` | 余额、本次消耗、卡池介绍、保底进度条、卡池下拉框、连抽次数下拉框、开始抽奖 / 概率公示 / 我的记录 / 管理面板 / 关闭 |
| **二次确认** | 点击「开始抽奖」（`require-confirmation: true` 时） | 卡池、次数、消耗、余额 + 确认 / 再想想 |
| **抽奖结果** | 抽奖完成后自动弹出 | 每个奖品（带品质颜色）、消耗与剩余、保底触发提示、背包已满提示 + 再来一次 / 返回主界面 / 关闭 |
| **概率公示** | 主界面「概率公示」按钮 | 按概率排序的全部奖品 + 权重 + 说明 |
| **保底进度** | 主界面保底区 / `/lottery pity` | 每条保底规则的当前次数、阈值、进度条、剩余次数 |
| **我的记录** | 主界面「我的记录」按钮 | 最近 15 条流水（时间、卡池、次数、奖品摘要） |
| **管理面板** | 主界面「管理面板」按钮（需 `paperlottery.admin`） | 运行状态、重载配置、模拟抽奖、重置我的保底 |

界面通过下拉框（`singleOption` 输入组件）+ 按钮回调（`DialogAction.customClick`）实现，
点击按钮时会读取下拉框的当前值，因此「选卡池 → 选次数 → 点抽奖」是连续操作，
无需反复关闭界面。

> **关于奖品图标**：原版对话框的 `item` 正文组件对物品数据格式要求非常严格
> （需要完整的数量区间与组件补丁），部分服务端构建会因此拒绝数据包、导致对话框打不开。
> 为保证 100% 可用，插件默认用「品质颜色 + 奖品名 + 数量」的文本正文；
> 如需真实物品图标，可设置 `settings.dialog.use-item-body: true`。

---

## 抽奖动画（开箱转盘）

抽奖的结算顺序是 **先算结果 → 播动画 → 再发奖**，因此动画期间不会重复扣费、也不会重复发奖。

### 三种动画类型

| `type` | 效果 | 说明 |
| --- | --- | --- |
| `inventory`（默认） | **箱子开箱式转盘** | 借用玩家快捷栏 9 格作为转盘，奖品图标横向滚动、逐渐减速，指针定格在结果上，类似 CS:GO 开箱 |
| `title` | 屏幕中央标题翻滚 | 奖品名快速跳动，最后定格 |
| `actionbar` | 物品栏上方文字滚动 | 开销最小 |
| `none` | 无动画 | 立即出结果 |

### 转盘是怎么工作的

* 动画开始时**备份**玩家前 9 格（快捷栏）并清空，把转盘画在这 9 格里；
* 指针初始就在中间格（`cursor-slot: 4`），先显示本轮结果，然后指针向右滚出；
* 指针在 0–8 之间循环前进，转盘内容同步向左滚动；
* 绕满一圈回到中间格时，指针处显示的正是本轮奖品（数学上恒成立，已单独验证）；
* 按 `slot-ticks` 曲线逐级减速，最后「咔哒」停在结果上；
* 连抽时逐次滚动，每次定格后重新加速。

### 安全性

动画会临时借用快捷栏，因此做了五重保护：

1. 动画期间取消所有点击 / 拖拽事件（`InventoryClickEvent` / `InventoryDragEvent`，`LOWEST` 优先级），玩家拿不走转盘上的展示物品；
2. 玩家主动关闭界面时立即结束动画并结算，不会吞掉这次抽奖；
3. 玩家退出时恢复快捷栏并结束动画；
4. 服务器关服 / `/lottery reload` 时 `shutdown()` 会恢复所有在线玩家的快捷栏；
5. 任何异常都会被捕获，直接跳过动画进入结算，玩家不会卡在转盘界面。

动画只在**客户端表现层**借用快捷栏，服务端会在同一 tick 内恢复备份，实测 64 钻石抽 5 钻石后剩余 59，无物品丢失。

### 配置

```yaml
animation:
  enabled: true
  type: inventory          # inventory / title / actionbar / none
  reel-size: 9             # 转盘格子数，必须 ≤ 54 且为 9 的倍数，超范围会自动收敛
  cursor-slot: 4           # 指针格（9 格时 4 为正中）
  settle-ticks: 15         # 定格停留时间
  max-animated-pulls: 10   # 单次最多为几次抽奖播动画
  roll-tick-ms: 55         # 匀速阶段每格耗时
  min-slot-tick-ms: 90     # 收尾阶段最慢每格耗时
  slot-ticks: [1, 2, 3, 5, 8, 13]   # 减速曲线
  show-names: true
  duration-ticks: 48       # title / actionbar 的滚动时长
  sounds:
    tick: "block.note_block.hat"
    win: "entity.player.levelup"
```

> 十连抽默认只播前 10 次动画，其余直接结算（受 `max-animated-pulls` 控制）。
> 若觉得太慢，可把 `type` 改成 `actionbar`，或调小 `settle-ticks` / `roll-tick-ms`。

---

## 配置文件详解

配置分成两个文件，各管一摊：

| 文件 | 负责内容 |
| --- | --- |
| **`rewards.yml`** | **卡池与奖品**（奖励、物品、权重、保底、限额） |
| `config.yml` | 全局设置、品质、货币、抽奖动画、提示文案 |

想改奖励只需要动 `rewards.yml`，改完执行 `/lottery reload`，无需重启。

> 兼容性：如果服务器上还留着老版本把 `pools` 写在 `config.yml` 里的配置，
> 插件会检测到并继续使用它（同时打印一条警告），方便平滑迁移。

### `rewards.yml` 速查

```yaml
pools:
  normal:
    display-name: "<gold>普通卡池"
    currency: vault            # 消耗的货币 ID
    cost:
      default: 500             # 单抽价
      10: 4500                 # 十连价
    amounts: [1, 5, 10]
    guarantee-rarity: uncommon # 每次至少保证的品质
    ten-pull-rarity: rare      # 连抽至少保证的品质
    announce-rarities: [legendary]
    pity:
      - rarity: epic
        threshold: 30
        reset-on-hit: true
    prizes:
      my_item:                 # 一个最简单的物品奖励
        display-name: "<aqua>我的奖品"
        rarity: rare
        weight: 50
        item:
          material: DIAMOND
          amount: 8
```

单个奖品支持的字段：`display-name`、`rarity`、`weight`、`bonus-weight`、
`item`（`material` / `amount` / `amount-min` / `amount-max` / `name` / `lore` / `enchants` /
`model-data` / `unbreakable` / `glow`）、`currency`（`id` / `min` / `max`）、
`commands`（`console:` / `player:` / `op:` 前缀）、`limit`、`player-limit`、`daily-limit`、
`conditions`。文件头部有完整的注释模板，直接复制即可。

### `settings`

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `default-pool` | `normal` | 默认打开的卡池 |
| `max-amount` | `100` | 单次最大连抽次数 |
| `default-amount` | `1` | 界面默认连抽次数 |
| `amount-options` | `[1, 5, 10]` | 下拉框可选连抽次数 |
| `require-confirmation` | `true` | 抽奖前二次确认 |
| `cooldown-millis` | `1500` | 抽奖冷却（毫秒） |
| `drop-when-full` | `true` | 背包满时是否掉落在地上 |
| `history-limit` | `50` | 每名玩家保留的流水条数 |
| `dialog.body-width` | `220` | 对话框正文宽度（1–1024） |
| `dialog.use-item-body` | `false` | 是否使用原版物品正文组件 |
| `dialog.after-action` | `close` | 按钮点击后行为：`close` / `none` / `wait` |
| `broadcast.enabled` | `true` | 全服播报开关 |
| `bonus.permission-multiplier` | — | 权限权重倍率，格式 `权限:倍率` |
| `bonus.weekend` | `1.0` | 周末全局权重倍率 |
| `bonus.hours` | — | 时段倍率，格式 `HH:mm-HH:mm:倍率`（支持跨零点） |

### `rarities`

顺序即稀有度等级（越靠后越稀有），用于保底、概率排序与播报。

```yaml
rarities:
  epic:
    display-name: "<light_purple>史诗"
    weight: 60
    broadcast: "<gray>[<gold>抽奖<gray>] <white>%player% <gray>抽到了 %rarity_color%%prize_name%"
```

### `pools.<id>`

| 键 | 说明 |
| --- | --- |
| `display-name` | 界面标题（MiniMessage） |
| `icon` | 图标材质（预留） |
| `description` | 卡池介绍文本行 |
| `currency` | 消耗货币 ID |
| `cost` | `default` 单次价格；也可写 `10: 4500` 为十连单独定价 |
| `amounts` | 该卡池可选连抽次数 |
| `permission` | 使用该卡池所需权限 |
| `conditions` | 卡池级条件（权限/世界/PAPI 变量） |
| `guarantee-rarity` | 每次抽取至少保证的品质 |
| `ten-pull-rarity` | 连抽（≥2 次）至少保证的品质 |
| `announce-rarities` | 抽到这些品质时全服播报 |
| `pity` | 保底规则数组 |

### `pools.<id>.prizes.<奖品ID>`

| 键 | 说明 |
| --- | --- |
| `display-name` | 奖品名（MiniMessage，支持占位符） |
| `rarity` | 品质 ID |
| `weight` | 基础权重（越大越常见） |
| `bonus-weight` | 固定权重加成 |
| `item` | 物品奖励：`material`、`amount` 或 `amount-min`/`amount-max`、`name`、`lore`、`enchants`、`model-data`、`unbreakable`、`glow` |
| `currency` | 货币奖励：`id`、`min`、`max` |
| `commands` | 命令奖励，支持 `console:` / `player:` / `op:` 前缀 |
| `limit` | 全服累计上限（`-1` 不限） |
| `player-limit` | 单名玩家累计上限 |
| `daily-limit` | 单名玩家每日上限 |
| `conditions` | 抽取条件：`permission`、`permission-any`、`permission-multiplier`、`placeholder`、`placeholder-min`、`worlds` |

---

## 命令与权限

### 命令

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/lottery` | 打开抽奖主界面 | `paperlottery.use` |
| `/lottery open [卡池]` | 打开指定卡池界面 | `paperlottery.use` |
| `/lottery draw [卡池] [次数]` | 直接抽奖（仍受二次确认配置影响） | `paperlottery.draw` |
| `/lottery pity [卡池]` | 保底进度面板 | `paperlottery.use` |
| `/lottery rates [卡池]` | 概率公示（控制台输出文本版） | `paperlottery.use` |
| `/lottery history [卡池]` | 我的抽奖记录 | `paperlottery.use` |
| `/lottery give <玩家> <卡池> <奖品> [数量]` | 发放奖品 | `paperlottery.admin` |
| `/lottery reset <玩家\|*> [卡池]` | 重置保底与统计 | `paperlottery.admin` |
| `/lottery reload` | 重载配置文件与数据 | `paperlottery.admin` |
| `/lottery info` | 运行状态、货币与卡池列表 | `paperlottery.admin` |

别名：`/lot`、`/choujiang`、`/cj`、`/draw`

### 权限

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `paperlottery.use` | 所有玩家 | 打开界面 |
| `paperlottery.draw` | 所有玩家 | 执行抽奖 |
| `paperlottery.pool.<卡池ID>` | — | 使用指定卡池（配合 `permission:` 字段） |
| `paperlottery.bonus.<名称>` | — | 权重加成（配合 `bonus.permission-multiplier`） |
| `paperlottery.admin` | OP | 管理命令与管理面板 |

---

## 概率与保底算法

### 概率

```
实际权重 = (weight + bonus-weight) × 权限倍率 × 卡池倍率 × 全局活动倍率
命中概率 = 实际权重 ÷ 所有可抽取奖品的权重之和
```

* 达到 `limit` / `player-limit` / `daily-limit` 的奖品会**移出轮盘**，
  剩余奖品的概率自动重新归一化。
* 若所有奖品权重都为 0，则退化为等概率抽取。
* 概率公示面板展示的正是归一化后的结果。

### 保底

```yaml
pity:
  - rarity: epic          # 目标品质
    threshold: 30         # 连续 30 次未获得 epic 及以上
    reset-on-hit: true    # 命中 epic 及以上时重置计数
```

**语义**：某品质的计数 = 「距上次获得该品质或更高品质，已经抽了多少次」。
计数达到 `threshold` 时，下一次抽取**强制**从该品质及以上的可用奖品中按权重抽取。

保底逻辑集中在 [`PityEngine`](src/main/java/cn/dsh/lottery/lottery/PityEngine.java)，并保证下列不变量：

| 不变量 | 说明 |
| --- | --- |
| **计数不超限** | 计数永远 ≤ 阈值。达到阈值后冻结在阈值上，直到命中后清零，界面不会出现 `31/30` 这类脏数据 |
| **就绪必兑现** | 规则就绪时，下一次抽取必然命中该品质或更高（前提是存在可发放的该品质奖品） |
| **高品质优先** | 多条规则同时就绪时兑现品质最高的那条，与 YAML 中的书写顺序无关 |
| **计数即真实间隔** | 计数与「距上次命中的抽数」完全相等，界面进度因此始终反映实际抽数 |
| **无幽灵计数** | 只为配置了保底规则的品质维护计数 |

> **升级提示**：旧版本会按「奖品品质」额外写入计数器，导致没有保底规则的品质
> （如 common / uncommon / rare）也留下计数，界面上就会看到与实际抽数无关的数字。
> 新版在加载 `playerdata.yml` 时会**自动清理**这些无效条目并打印日志，无需手动处理。
>
> 如果服务器上已经有人被旧版本的计数卡住（例如计数停在 30/30 却一直不出货），
> 迁移后保底会立即正常兑现；也可用 `/lottery reset <玩家> <卡池>` 重置单个玩家的进度。

* 未命中时对应计数 +1，命中时按 `reset-on-hit` 决定是否清零。
* 若某品质的奖品全部达到限额（`limit` / `player-limit` / `daily-limit`），
  该保底会暂时无法兑现；插件会**每 60 秒最多一次**在控制台输出可诊断的告警，
  提示你去检查限额配置，而不是静默地让玩家白抽。
* 保底进度在对话框中以进度条实时展示。

---

## 数据存储

数据保存在 `plugins/PaperLottery/playerdata.yml`：

```yaml
players:
  <UUID>:
    name: Steve
    totals:            # 卡池 -> 累计抽奖次数
      normal: 120
    counters:          # 卡池 -> 品质 -> 连续未命中次数（保底）
      normal:
        epic: 12
        legendary: 45
    prizes:            # 奖品 -> 累计获得次数
      diamond: 7
    pool-prizes:
      normal:
        diamond: 7
    daily:             # 日期 -> 奖品 -> 当日获得次数（保留 30 天）
      "2026-10-04":
        elytra: 1
    history:           # 最近流水
      - {time: 1791043000000, pool: normal, amount: 10, currency: vault, cost: 4500.0, summary: "..."}
global:
  prize-totals:        # 奖品 -> 全服累计发放次数（用于 limit 判定）
    netherite_sword: 3
```

* 每 5 分钟自动保存一次（仅在数据变动时写盘），玩家退出与插件卸载时也会保存。
* `/lottery reload` 会先落盘再重载，避免丢失计数。

---

## 开发者 API

```java
// 监听抽奖前置事件：取消或修改次数
@EventHandler(ignoreCancelled = true)
public void onPreDraw(LotteryPreDrawEvent event) {
    if (event.getPool().id().equals("normal") && isDoubleEvent()) {
        event.setAmount(event.getAmount() * 2);
    }
}

// 监听抽奖结算事件：做成就 / 统计
@EventHandler
public void onDraw(LotteryDrawEvent event) {
    DrawResult result = event.getResult();
    result.player().sendMessage(Component.text("你抽到了 " + result.counts()));
}

// 打开界面
PaperLotteryPlugin plugin = PaperLotteryPlugin.get();
plugin.ui().openMain(player);
plugin.ui().draw(player, plugin.lotteryConfig().pool("normal"), 10);
```

关键类型：

* `cn.dsh.lottery.lottery.LotteryService` —— 抽奖核心（`draw` / `preview` / `rates`）
* `cn.dsh.lottery.model.Pool`、`Prize`、`DrawResult`
* `cn.dsh.lottery.currency.Currency` —— 自定义货币只需实现该接口并在 `CurrencyManager` 注册
* `cn.dsh.lottery.ui.DialogManager` —— 全部对话框构建逻辑

---

## 实机验证结果

本项目已在 **真实 Paper 26.2 服务端（26.2-129-9240f58，Java 25）** 上完成验证：

| 验证项 | 结果 |
| --- | --- |
| 插件加载与启用 | ✅ 无异常，正确报告 Dialog API 可用 |
| Dialog 构建（主界面 / 结果 / 概率 / 保底 / 记录 / 管理） | ✅ 全部通过 Paper 真实 `Dialog.create()` 构建，无非法数据 |
| 概率归一化 | ✅ 12 个奖品权重之和归一化为 `1.000000`，与手算一致（500/380/… → 27.8%/21.1%/…） |
| 真实抽奖链路 | ✅ 扣除 15 钻石 → 发放 `IRON_BLOCK×4 + COAL×32` → 库存 64→49 |
| 保底强制命中 | ✅ 计数达阈值后强制抽出目标品质，并正确清零计数 |
| 限额/冷却/余额/货币校验 | ✅ 分别返回 `draw.cooldown` / `draw.insufficient` / `draw.currency-missing` |
| 数据持久化 | ✅ 累计次数、奖品计数、每日计数、流水记录均正确写入 |
| 配置热重载 | ✅ `/lottery reload` 生效 |
| Vault 缺失降级 | ✅ 给出明确警告并跳过相关货币，其余货币照常工作 |
| `rewards.yml` 独立加载 | ✅ 正确报告 `source=rewards.yml pools=[normal, premium, diamond]` |
| 转盘结果定格 | ✅ 5/5 轮模拟中，结果都精确落在指针格（`ticks=9 win=0 ptr=9`） |
| 转盘减速曲线 | ✅ 实测 `0:90ms 1:90ms 2:96ms 3:156ms 4:156ms`，符合「先快后慢」 |
| 转盘动画真实运行 | ✅ 在服务端完整跑完并触发结算回调（`finished=true stillAnimating=false`） |
| 快捷栏零丢失 | ✅ 备份 `[DIAMONDx59, COALx16]` → 恢复后完全一致；64 钻石抽 5 钻石后剩 59 |
| 动画异常兜底 | ✅ 动画抛异常时自动跳过并直接结算，玩家不会卡住 |
| **保底不变量测试** | ✅ 10010 项断言全部通过（见下表） |

保底系统的专项不变量测试（在真实服务端上跑产品代码 `PityEngine`）：

| 不变量 | 测试方式 | 结果 |
| --- | --- | --- |
| I1 计数不超限 | 2000 次模拟抽取，每抽断言所有计数 ≤ 阈值 | ✅ 最大 epic=30/30、legendary=90/90 |
| I2 就绪必兑现 | 3000 次抽取中，凡抽取前已有就绪规则则断言本次命中该品质 | ✅ 73 次就绪全部兑现（73/73） |
| I3 高品质优先 | 同时把 epic 与 legendary 推到就绪，并测试 YAML 顺序颠倒 | ✅ 两种情况都选中 legendary |
| I4 计数即真实间隔 | 3000 次抽取，逐抽比对计数与「距上次命中」 | ✅ 最大偏差 = 0 |
| I5 无幽灵计数 | 500 次抽取后检查计数品质集合 | ✅ 仅 `[epic, legendary]`，与规则一致 |
| 旧数据清理 | 造出 `common=178/uncommon=90/rare=20/epic=12/legendary=45` 后清理 | ✅ 删除 3 条幽灵计数，保留 epic=12、legendary=45 |

> ⚠️ **注意**：默认配置中 `normal` 卡池使用 `vault` 货币，`premium` 使用 `points`。
> 若服务器**未安装 Vault + 经济插件**，这两个卡池会提示「货币不可用」。
> 此时可先把卡池货币改成 `xp` 或 `diamond`（内置货币，零依赖），或安装 Vault。

---

## 常见问题

**Q：对话框打不开 / 客户端闪退？**
A：确认服务端为 Paper 26.2（或 1.21.6+ 且支持 Dialog API）。若使用了
`settings.dialog.use-item-body: true`，请改回 `false` —— 原版 item 正文组件对数据格式要求严格，
部分服务端构建会拒绝该数据包。

**Q：提示「货币不可用」？**
A：该卡池引用的货币未成功注册。检查 `currencies` 配置、Vault 与经济插件是否正常启用，
可用 `/lottery info` 查看每种货币的可用状态。

**Q：界面上的保底进度为什么不跟着抽数走？**
A：这是 v1.0.0 的缺陷，已修复。旧版本有两个问题：
①每次中奖都会按「奖品品质」额外自增计数器，导致 common/uncommon 等没有保底规则的品质也留下计数；
②计数越过阈值后不再冻结，界面会出现 `31/30` 这类数字。
现在计数严格等于「距上次命中该品质的抽数」并封顶在阈值，界面与实际抽取完全一致。
旧数据会在加载时自动清理。

**Q：有玩家超过保底却一直不出货？**
A：v1.0.0 的缺陷，已修复。当时若某品质的奖品全部达到限额（或该品质根本没有奖品），
强制保底会落空，而计数仍继续增长，玩家就会看到「明明超过保底却不出货」。
现在：
①保底规则就绪时优先兑现**品质最高**的那条，不会被低品质规则抢先；
②真的无法兑现时会每 60 秒最多一次输出控制台告警，指明是哪个卡池/品质以及可能原因。

**Q：`vault-multi` 货币报错？**
A：并非所有经济插件都实现了多货币接口。PlayerPoints、GemsEconomy 支持；
EssentialsX 只支持主经济（请使用 `type: vault`）。

**Q：如何做「十连必出史诗」？**
A：设置 `ten-pull-rarity: epic`。若要求「十连中至少 2 件史诗」，可再用
`guarantee-rarity` + `pity` 组合，或在 `LotteryPreDrawEvent` 中自定义逻辑。

**Q：能按玩家等级动态调整概率吗？**
A：可以。安装 PlaceholderAPI 后使用条件与权限倍率：

```yaml
conditions:
  placeholder-min:
    - "%player_level%:30"
permission-multiplier:
  - "server.vip:1.5"
```

**Q：抽奖是异步的吗？**
A：抽奖全程在主线程同步执行并做原子结算（先扣费、失败自动退回），
同时用「忙碌标记」防止连点重复扣款，因此不会出现刷奖或扣款不一致。

**Q：动画期间玩家能动背包里的东西吗？会不会丢物品？**
A：不会。动画期间点击与拖拽事件全部被取消；快捷栏内容在动画开始前备份、结束/退出/关服时原样恢复。
实测备份 `[DIAMONDx59, COALx16]` 恢复后完全一致。

**Q：转盘转得太久 / 想更快？**
A：调小 `animation.settle-ticks`（定格停留）与 `animation.roll-tick-ms`（每格耗时），
或把 `animation.type` 改成 `actionbar`，也可直接 `enabled: false` 关闭动画。
十连抽的播放次数由 `max-animated-pulls` 控制。

**Q：`reel-size` 填了 18 或 81 会怎样？**
A：`81` 超出原版自定义容器上限（54），会被自动收敛为 `54` 并在控制台给出提示。
`reel-size` 决定转盘窗口的行数，但动画只借用玩家快捷栏的前 9 格，
因此 `18` 及以上时第二行起只是装饰性玻璃板。**建议保持 `9`。**

**Q：怎么把某个奖励改成别的物品？**
A：只改 `rewards.yml`：

```yaml
pools:
  normal:
    prizes:
      dragon_egg:              # 奖品 ID
        display-name: "<gold>龙蛋"
        rarity: legendary
        weight: 1
        item:
          material: DRAGON_EGG # 换材质
          amount: 1
```

改完执行 `/lottery reload`，然后 `/lottery rates normal` 就能看到新概率。

---

## 许可

本插件为示例项目，可自由修改与分发。
