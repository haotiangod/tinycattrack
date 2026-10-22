# TinyCat 数据流与统计口径

> 这份文档用自然语言描述 TinyCat 当前实际运行逻辑，便于核对 bug。
> 代码位置以 `src/main/java/tinycat/` 为准。

## 1. 从游戏到 `.run`

```text
游戏事件
  → tinycat.java 订阅 BaseMod 事件
  → Run 采集单局数据
  → .run JSON 文件（RunHistoryPlus 写出）
  → RunHistoryManager 批量读取
  → Run.valid() 过滤
  → ClassStat 聚合
  → MoreStatsScreen / StatsScreenPatch 渲染
  → TierListBuilder / TierImageExporter / TierListExporter 导出
```

### tinycat.java

- `@SpireInitializer` 标记入口。
- 构造时创建 `Config`，`BaseMod.subscribe(this)`。
- `initialize()`：创建单例、加载 `Localization`、`NameMapper`。
- `PostInitialize`：创建 `RunHistoryManager`，注册 badge 和设置面板。
- `PostDungeonInitialize`：抓当前玩家 HP，作为第 0 层 HP，写 `runs/{runDir}/{开局时间戳}.floor0hp`。

已知问题：`capturedFloor0HP` 是静态字段；批量加载历史局时会污染 floor 1 战损。现在 `RunHistoryManager.refreshData()` 会在加载期间临时置空。

### RunHistoryManager

- 只扫描当前存档位对应的 `runs/` 子目录。
- 排除 `gold_track`。
- 每个文件交给 `Run.fromFile()`。
- `Run.valid()` 过滤：
  - 必须是 A20；
  - 不能是每日、特殊局、无尽；
  - 不能含 Mod 卡（名称含 `:`）；
  - 命中硬编码黑名单的对局剔除。
- 种子局保留，由统计面板的种子筛选（全部/不含/仅种子）控制。
- 按角色放入 Ironclad / Silent / Defect / Watcher 四个角色列表；另有 All 汇总列表，All 不是第五个角色。

### Run

- `RunData` 是 `.run` 的 Gson 映射。
- 构造时解析角色、碎心、A20、时间戳、放弃状态、卡组、遗物、Boss 遗物、卡牌选择、战斗伤害等。
- `valid()` 做上面那套过滤。
- `getAct(floor)`：`<=17` 第一幕，`<=34` 第二幕，`<=52` 第三幕，否则第四幕；传送门事件特殊处理。
- `.run` 文件实际是 GBK；现在 `readRunText()` 严格尝试 UTF-8 → GBK → 系统默认，并去 BOM。

## 2. 卡牌抓率

### Run 的卡牌选择

`Run` 遍历 `runData.card_choices`：

- `picked`：被选中的卡。
- `not_picked`：没被选的卡。
- 如果选的是普通卡，会额外把 `SKIP` 或 `Singing Bowl` 加入 `not_picked`。

### ClassStat.cardPickStats

- `floor >= 17` → `cardPicksAfterAct1`。
- 否则 → `cardPicksAct1`。
- 被选中的卡：`Rate.win++`。
- 没被选的卡：`Rate.loss++`。
- 因此：
  - `Rate.win = picked`
  - `Rate.total = offered`
  - 抓率 = `win / total`

注意：这里**没有**用 `Run.getAct(floor)` 分幕。floor 17 是 Act 1 Boss 后的选牌，现有口径归到“第一幕后”，和统计页一致。

### Tier 导出

`TierListBuilder` 复用 `cardPicksAct1 / cardPicksAfterAct1`：

- `Singing Bowl` 并入 `SKIP`。
- `CURSE/STATUS` 卡剔除。
- 按 `picked/offered` 重新算 `rate`，再分档。

## 3. Boss 遗物抓率

`ClassStat.bossRelicStats`：

- 第 1 次 Boss 遗物选择 → Act 1。
- 第 2 次 → Act 2。
- `picked` 为空或 `"SKIP"` → `"SKIP"`。
- 被选中的遗物 `win++`，没被选的 `loss++`。
- 所以 `win/total` 就是抓率。

Tier 导出按 Act 1 / Act 2 两组，SKIP 保留，图片来自 `RelicLibrary`。

## 4. 战损

### 数据字段

`.run` 里有：

- `damage_taken`：每场战斗的 `damage`、楼层、回合数、敌人。
- `current_hp_per_floor`：每层结束后的 HP。
- `max_hp_per_floor`：每层最大 HP。

### 第 0 层 HP

`Run.getFloor0HP()`：

1. `tinycat.capturedFloor0HP`（当前运行时捕获）。
2. `runs/{runDir}/*.floor0hp` 伴生文件：
   - 先找精确文件名；
   - 找不到则按 `结束时间 - playtime` 估算开局时间，找 24 小时内最接近的文件。
3. 估算法：
   - Ironclad：`floor1HP + floor1Damage - 6`（Burning Blood 回 6）；
   - 其他角色：`floor1HP + floor1Damage`。
4. `max_hp_per_floor[0]`。

### 每场战斗的实际战损

```text
hpBefore = 第 1 层用 floor0HP，其他层用上一层结束 HP
hpAfter  = 本层结束 HP
actualHPLoss = hpBefore - hpAfter
```

- 正数 = 掉血。
- 负数 = 回血，保留负值。
- `damage` 是战斗内伤害，`actualHPLoss` 是净变化。

### ClassStat

`encounterStats()` 按幕累计：

- 平均实际战损；
- 平均无回复/有回复战损；
- 每幕总战损；
- 战斗/事件分开统计。

## 5. 金币

`Run.parseGold()`：

1. 优先读 `runs/gold_track/{runDir}/{timestamp}.txt` 的 `gold_own`。
2. 没有则用 `gold_per_floor`：
   - 第一层金币 - Neow 开局扣除；
   - 后续正增量；
   - MawBank +12。

这个值是**累计获得金币的估算**，不是最终剩余金币。

`.run` 里的 `gold` 是最终剩余金币，精确，但当前没直接使用。

`ClassStat.averageGold` 对 `run.gold` 求平均。当前口径已确认：**累计获得金币，估算值**；UI 会显示“（估算）”，CSV 会带 `gold_estimated` 说明。

`.run` 里没有累计获得金币字段，只有最终剩余金币 `gold`；逐事件精确累计需要额外数据源（StateNotes 许可证/接入见 `STATE-NOTES-AND-GOLD.md`）。

**第 1 层战损**：`.run` 的第 1 层 HP 不可靠，当前用“第 1 层受到伤害”估算；Ironclad 再减 6（Burning Blood）。UI 会显示“第1层战损：估算”。

## 6. Rate 的语义

`Rate` 的 `win/loss` 含义由调用方决定：

- 卡牌/Boss 遗物：`win` = 被选，`loss` = 没被选，`percent` = 抓率。
- 火堆/商店：`win` = 该局胜利，`loss` = 该局失败，`percent` = 胜率，不是选择率。

这是后续重构要明确的点，避免把“胜率”误读成“选择率”。

## 7. 导出

```text
StatsScreenPatch 当前页面
  → TierListBuilder（按 scope）
  → TierImageExporter（缓存卡图/遗物图）
  → TierListImageRenderer（tierlist.jpg/png）
  → TierListExporter（CSV/JSON/HTML/README/zip）
```

导出范围：

- 总体统计 → `ALL`：四组 Tier List + 全部统计 CSV
- 第一幕卡牌选取率 → `cards_act1`
- 第一幕后卡牌选取率 → `cards_after_act1`
- BOSS 遗物选取率 → `boss_relics_act1 + boss_relics_act2`

按钮只在上述页面出现；其他页面不显示。

CSV 文件：

- `data.csv`：Tier List 条目；每行带 `character_id`、`character_kind`（`character`/`aggregate`）和 `seed_filter`
- `summary.csv`：角色/筛选/基础统计/估算标记；`character_count=4`，`ALL` 是四角色汇总
- `all_stats.csv`：长表，覆盖所有 ClassStat 维度（卡牌、Neow、Boss 遗物、战斗、事件、遗物、删牌、火堆、商店、Meta 等），开头带角色/种子筛选上下文

种子筛选会改变抓率样本：同一张卡在包含/排除种子局时可能跨过档位边界；`min_sample` 过小（尤其 0）会让 1/1 这种小样本直接进入 S 档。

## 8. 金币估算校验

脚本：`scripts/validate_gold_estimate.py`

在 1113 个真实 `.run` 上运行的结果：

- `gold_track` 精确文件：0 个
- 估算累计金币始终 ≥ 最终金币（1113/1113）
- 估算 - 最终 的平均差：813.25，中位数 761
- 没有“无消费局”可用于隔离 Neow 起始金币误差
- 第 1 层战损估算（Ironclad damage-6）均值约 -0.10，中位数 0.00；其他角色均值 1–3

结论：当前 `.run` 无法验证累计金币真值；公式只能保证“累计估算 ≥ 最终剩余”，差额主要是消费。要精确必须接逐事件金币数据源，且需许可证。

## 9. 当前重构状态

已完成：

- 名称映射统一到 `CardNameNormalizer`
- `.run` UTF-8/GBK
- floor0 HP 隔离与伴生文件匹配
- `Utils.round` 改为小数位
- 角色颜色按索引
- 图片缓存
- `tierlist.jpg/png`
- 总体页导出全部统计 CSV（`summary.csv` + `all_stats.csv`）
- 修复 `averageScore` 被重复累加、以及 `eventStats` 为空时跳过 deck/gold 统计的问题

待做：

- `ClassStat` 拆分 collector
- `StatsScreenPatch` 拆分 renderer
- `MoreStatsScreen` 5 份 ClassStat 缓存/增量
- 导出后台化/进度
- StateNotes 授权后接精确金币/HP
