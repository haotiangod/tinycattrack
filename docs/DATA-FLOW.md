# TinyCat 数据流与统计口径

> 描述当前 4.2.2 的实际逻辑，便于核对统计结果。
> 代码位置以 `src/main/java/tinycat/` 为准；测试 Mod 在 `tracker-test/`。

## 1. 从游戏到统计界面

```text
RunHistoryPlus 写出 .run JSON
  → RunHistoryManager 批量读取
  → Run 读取可选 .tinycat（仅由独立测试 Mod 写入）+ 解析 .run
  → Run.valid() 过滤
  → ClassStat 聚合
  → MoreStatsScreen / StatsScreenPatch 渲染
  → TierListBuilder / TierImageExporter / TierListExporter 导出
```

`TinyCatTrackerTest` 是独立测试 Mod，不在主 JAR 内；它订阅 `PostDungeonUpdate`，只负责写状态文件，不参与统计渲染。

### tinycat.java

- `@SpireInitializer` 标记入口。
- 构造时创建 `Config`，`BaseMod.subscribe(this)`。
- `initialize()`：加载 `Localization`、`NameMapper`。
- `PostInitialize`：创建 `RunHistoryManager`，注册 badge 与设置面板。
- 主 Mod 不订阅 `PostDungeonUpdate`，也不写 `.tinycat`。

### TinyCatTrackerTest（可选）

- 文件：`runs/{runDir}/{start_ts}.tinycat`，key=value 纯文本。
- `runDir` 与 RunHistoryPlus 一致：默认存档位为角色名，其他存档位为 `{slot}_{角色名}`。
- 只在楼层或 `CardCrawlGame.goldGained` 变化时写文件，避免每帧 IO。
- 进入第 1 层且 `playtime < 300` 时记录 `floor0_hp` 和 `start_gold`；读档续玩不重复记录。
- 楼层刷新后，按 `now - playtime` 在 1 小时窗口内寻找最近的 `.tinycat` 重新挂接。
- 构建：`gradle buildTrackerTestJAR`；与主 Mod 同时放入 `mods/` 即可。

### RunHistoryManager

- 只扫描当前存档位对应的 `runs/` 子目录，排除 `gold_track`。
- 只读取 `.run` 文件，交给 `Run.fromFile()`；单个文件解析失败会被跳过。
- `Run.valid()` 过滤：A20、非每日/特殊/无尽、无 Mod 内容、黑名单。
- 种子局保留，由统计面板的种子筛选控制。
- 按角色分为四个列表；All 是四角色汇总，不是第五个角色。

### Run

- `RunData` 是 `.run` 的 Gson 映射。
- 构造顺序：解析基础字段 → `readTrackedState()` 匹配 `.tinycat` → `captureBossFloors()` → 计算 HP/金币/伤害。
- `.run` 文本严格尝试 UTF-8 → GBK（去 BOM），兼容非中文 locale。

## 2. 幕划分

`Run.getAct(floor)` 不按固定楼层，而是从 `damage_taken` 识别 Boss 实际楼层：

- 第一幕 Boss：Hexaghost / Slime Boss / The Guardian，取最小楼层。
- 第二幕 Boss：Automaton / Collector / Champ，取最小楼层。
- 第三幕 Boss：Time Eater / Awakened One / Donu and Deca，取最大楼层。
- 第一幕：`floor <= act1BossFloor`。
- 第二幕：`floor <= act2BossFloor`。
- 第三幕：`floor <= act3BossFloor`。
- 其余为第四幕。
- 缺少 Boss 数据时回退 `<=17 / <=34 / <=52`；传送门路线用 `portalFloor` 特殊处理。
- Mind Bloom 的 Boss 战在数据中名为 `"Mind Bloom Boss Battle"`，不会干扰识别。

战损统计使用 `Run.getActForStats(floor)`：

- `path_per_floor` 为 `T` 的宝箱层返回 0，调用方跳过；
- 各幕 Boss 的下一层是奖励/过渡层，也返回 0；
- 其余返回 `getAct(floor)`。

因此每幕统计范围是“捏奥之后 ~ 本幕 Boss”：第一幕第 1 层到第一幕 Boss，第二幕从第一幕 Boss 后开始，依此类推。`getLastActForStats()` 从 `floorsReached` 向前跳过奖励层，用于死亡幕归属。

真实数据（1,113 局）分布：

| 幕 | 常规 Boss 层 | 偏移情况 |
|---|---|---|
| 第一幕 | 16 | 17（15 局） |
| 第二幕 | 33 | 34（40 局） |
| 第三幕 | 51 | 52（63 局）、53（5 局）、50（1 局） |
| 第四幕 | 矛盾 55–58，心脏 56–59 | 篝火 53/54，商店 54/55 |

旧边界 `<=17/<=34/<=52` 只会把 5 条 52/53 层的第三幕 Boss 误归第四幕；新算法 Boss 归属 0 误判。

## 3. 卡牌抓率

`Run` 遍历 `runData.card_choices`：

- `picked`：被选中的卡。
- `not_picked`：没被选的卡；跳过时额外计入 `SKIP` / `Singing Bowl`。

`ClassStat.cardPickStats`：

- `floor >= 17` → `cardPicksAfterAct1`，否则 → `cardPicksAct1`。
- 被选中的卡 `win++`，没被选的卡 `loss++`。
- 抓率 = `win / total`。
- 这里不用动态幕边界：17 层是 Act 1 Boss 后的选牌，统计页统一归入“第一幕后”。

Tier 导出复用这两组数据：`Singing Bowl` 并入 `SKIP`，剔除 `CURSE/STATUS`，按 `picked/offered` 重算并分档。

## 4. Boss 遗物抓率

- 第 1 次 Boss 遗物选择 → 第一幕；第 2 次 → 第二幕。
- `picked` 为空或 `"SKIP"` → `SKIP`。
- 被选中 `win++`，未选中 `loss++`，`win/total` 为抓率。
- 交换 Boss 遗物单独统计。

## 5. 战损

### 数据来源

- `damage_taken`：每场战斗的伤害、楼层、回合、敌人。
- `current_hp_per_floor`：每层结束后的 HP。
- `max_hp_per_floor`：每层最大 HP。

### 第 0 层 HP

`Run.getFloor0HP()`：

1. `.tinycat` 的 `floor0_hp`（由测试 Mod 精确记录，需开局第 1 层 300 秒内写入）。
2. 估算：`floor1HP + floor1Damage`；Ironclad 再减 6（Burning Blood）。
3. `max_hp_per_floor[0]`。
4. 兜底 70。

### 实际战损

```text
hpBefore = 第 1 层用 floor0HP，其他层用上一层结束 HP
hpAfter  = 本层结束 HP
actualHPLoss = hpBefore - hpAfter
```

- 正数掉血，负数回血，保留负值。
- `damage` 是战斗内伤害，`actualHPLoss` 是净变化。
- 第 1 层 HP 不可靠时在 CSV 保留估算计数，界面不再显示该提示。

### ClassStat

`encounterStats()` 按幕累计：

- 平均实际战损；
- 平均无回复/有回复战损；
- 每幕总战损；
- 战斗与事件分开统计。

战损按 `getActForStats` 跳过宝箱层与 Boss 后奖励/过渡层。每幕总战损是各战斗与事件净损失之和，篝火等回血没有对应条目、不会被抵减，因此它表示“本幕累计受到的伤害”，不等于“本幕开场 HP − Boss 后 HP”。

## 6. 金币

`Run.parseGold()`：

1. `.tinycat` 的 `gold_gained`（由测试 Mod 从 `CardCrawlGame.goldGained` 写入，精确累计获得金币）；主 Mod 只读，不生成文件。
2. 没有状态文件时用 `gold_per_floor` 估算：
   - 第一层金币 - Neow 扣除常数；
   - 后续正增量；
   - MawBank +12。
3. 估算结果 `goldEstimated=true`，UI/CSV 标注。

`.run` 的 `gold` 是最终剩余金币，精确，但不是本 Mod 统计的“累计获得金币”口径。

金币估算在 305 局可提取真值的数据上：平均绝对误差 38.0，中位 39，P90 64，最大 160，带符号平均 -2.5。详见 `STATE-NOTES-AND-GOLD.md`。

## 7. Rate 的语义

`Rate` 的 `win/loss` 由调用方决定：

- 卡牌/Boss 遗物：`win` = 被选，`loss` = 没被选，`percent` = 抓率。
- Neow：选取率与胜率是两组独立的 Rate。
- 战斗/事件/火堆/商店：`win` = 该局胜利，`loss` = 该局失败，`percent` = 胜率。
- 火堆/商店行动同时展示 `(win/total)` 次数，便于区分样本量。

## 8. 导出

```text
StatsScreenPatch 当前页面
  → TierListBuilder（按 scope）
  → TierImageExporter（缓存卡图/遗物图）
  → TierListImageRenderer（tierlist.jpg/png）
  → AllStatsExporter（按统计页生成中文 .xlsx）
  → TierListExporter（CSV/JSON/HTML/README/zip）
```

- 导出文件默认中文：表头、工作表名、指标名、说明均为中文；卡牌/遗物/事件保留英文名辅助列。
- 全部导出 → 四组 Tier List + 12 个 `.xlsx`：
  - `总体统计.xlsx`：全角色基础、战斗与收益、删牌统计、当前筛选汇总、Meta 成长
  - `第一幕卡牌选取率.xlsx`、`第一幕后卡牌选取率.xlsx`
  - `卡组中的卡牌胜率.xlsx`
  - `尼奥奖励.xlsx`：尼奥胜率、尼奥选取率
  - `BOSS遗物.xlsx`：第一/二幕选取率、第一/二幕胜率、交换遗物胜率
  - `遭遇战伤害.xlsx`：战损、受到伤害、有无回复
  - `遭遇战死亡率与药水.xlsx`：死亡率、药水使用
  - `遗物胜率.xlsx`：遗物胜率、购买遗物胜率
  - `各幕事件胜率.xlsx`：第一/二/三幕
  - `火堆行动.xlsx` / `商店行动.xlsx`：第一幕至第四幕
- 同类页面合并为一个工作簿；卡牌选取率仍按第一幕/第一幕后拆成两个文件。
- 单页导出（第一幕卡牌 / 第一幕后卡牌 / Boss 遗物）只写对应工作簿，避免生成无关统计。
- `.xlsx` 由项目内 `XlsxWriter` 生成最小 OOXML，不引入 Apache POI 或其它第三方依赖。
- 导出按钮位于页面右上角，不再遮挡内容；成功/失败只显示提示，路径写日志。

CSV：

- `data.csv`：Tier 条目，中文表头与中文取值，仍保留角色 ID、分组、梯度等机器可读字段。
- Tier List 的分页/样本上限与统计界面一致：卡牌前 200、火堆每幕前 35、商店每幕前 32；最小样本过滤在名额截断之后。

种子筛选会改变抓率样本，小样本（尤其 `min_sample=0`）容易跨档。

## 9. 怪物危险度梯度

```text
统计页当前筛选（日期/角色/放弃局/种子局）
  → MonsterTierBuilder（逐场 EncounterStats.actualHPLoss 按幕归类）
  → MonsterTierModel
  → MonsterTierExporter（xlsx/csv/json/html/zip）
  → MonsterTierImageRenderer（png/jpg）
```

- 只统计 `path_per_floor` 为 `M`/`E` 的非 Boss 战与 `B` 的 Boss 战；事件、宝箱、火堆、商店与第四幕固定战不计入。
- 主指标是平均实际战损（战前 HP − 战后 HP，正值掉血，负值回血），中位数、P90、95% CI 只作辅助。
- Boss 不与普通怪混排，按幕内平均战损降序排为 T0；非 Boss（含精英）按同样顺序等人数分 T1-T4，T1 最高危。
- 样本门槛：Boss ≥ 5；单角色非 Boss ≥ 10；全角色汇总非 Boss ≥ 30。门槛不读取统计页的最小样本下拉框。
- 低于门槛的条目保留在“样本不足”区：`tier=null`、排名为空，仍输出样本数、样本占比、净战损合计与战损贡献占比。
- 样本占比 = 该遭遇样本数 / 同幕同类（Boss 或非 Boss）样本总数；战损贡献占比 = 该遭遇净战损 / 同幕同类净战损合计，后者 ≤ 0 时为空。
- JSON `schema_version=2`；`low_sample=true` 与 `tier=null` 表示未参与分档。
