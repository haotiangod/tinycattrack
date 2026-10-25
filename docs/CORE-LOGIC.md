---
title: TinyCat 核心计算逻辑（AI 上手指南 / remand）
summary: 所有统计指标的公式、分母口径、数据来源、代码位置、已知坑与修改协议；改任何统计逻辑前先读这里
read_when:
  - 需要理解或修改 TinyCat 的任何统计口径
  - 用户质疑"数字对不对/是不是被改过"
  - 新增统计维度（采集 → 聚合 → 渲染 → 本地化）
canonical_for: 统计口径（唯一权威）
change_log: docs/ALGORITHM-AUDIT.md
data_flow_doc: docs/DATA-FLOW.md
last_updated: 2026-10-23
---

# 0. 30 秒速览

- 数据源只有一个：`runs/**/*.run`（RunHistoryPlus 写出的 JSON）+ 可选的 `.tinycat`（测试 Mod 写的金币/首层 HP）。
- 所有聚合都在 `ClassStat.StatCollector` 里：**遍历每局 → 每个维度各加一次 → `finalise()` 排序成 List**。
- UI 和导出**不做二次计算**，只读 `ClassStat` 的字段；所以数字对不上时，先看 `ClassStat`，别去改 UI。
- **两个最容易搞错的分母**：
  - 「平均受到伤害 / 平均战损 / 药水 / 死亡率」的分母是**遭遇次数**（同一局打两次 = 两个样本）。
  - 「每幕总战损」的分母是**局数**（每局先把该幕所有战损求和，再按局平均）。
- **本 Mod 的"胜"= 心脏通关**（`isHeartKill`），不是 `victory`。所有胜率、连胜、存活率都基于它。

---

# 1. 数据流

```
runs/{slot}_{CLASS}/*.run          RunHistoryPlus / 游戏写出的对局 JSON
        │
        ├── RunHistoryManager.loadData()     目录/存档位过滤 → Run.fromFile → Run.valid()
        │
        ├── Run（一局）                       解析 .run + 读取可选 .tinycat（金币/首层HP）
        │
        ├── ClassStat.StatCollector.collect() 逐局逐维度累加到 Map<T, Rate/Mean>
        │
        └── ClassStat.finalise()              Map → 排序后的 List，赋给 public 字段
                    │
                    ├── StatsScreenPatch / CharStatRenderer / MoreStatsScreen   界面渲染
                    └── tier/AllStatsExporter · TierListExporter · MonsterTierExporter   导出
```

一句话：**改聚合只动 `ClassStat`，改展示只动 `ui/` `patches/` `tier/`，两边不要混。**

---

# 2. 字段映射（外部 JSON → 内部字段）

| `.run` 字段 | `Run` 字段 | 用途 / 备注 |
|---|---|---|
| `damage_taken[]` | `encounterStats: List<EncounterStats>` | 每场战斗一条：`floor/enemies/damage/turns` |
| `current_hp_per_floor[]` | 同上 → `actualHPLoss` | 第 n 层结束 HP；`actualHPLoss = 前一层结束HP − 本层结束HP` |
| `gold_per_floor[]` | `gold`（+`goldEstimated`） | 每层累计持有金币；估算时必须标 `goldEstimated=true` |
| `path_per_floor[]` / `path_taken[]` | `getPathNode()` / 问号统计 | `M`普通 `E`精英 `B`Boss `?`事件 `$`商店 `R`篝火 `T`宝箱 |
| `card_choices[]` | `cardChoices: List<CardChoice>` | `floor/picked/not_picked` |
| `event_choices[]` / `event_stats` | `eventStats` | 事件名、`damage_taken`、`cards_removed` |
| `boss_relics[]` | `bossRelicChoiceStats` | 第 0 项=第一幕，第 1 项=第二幕 |
| `campfire_choices[]` | `campfireChoices` | `key`=REST/SMITH/PURGE/DIG…，SMITH 时 `data`=卡名 |
| `items_purchased[]` + `item_purchase_floors[]` | `itemsPurchased` / `itemPurchaseFloors` | 商店购买明细 |
| `items_purged[]` + `items_purged_floors[]` | `itemsPurged` / `itemsPurgedFloors` | 商店删牌 |
| `relics[]` / `relics_obtained[]` | `relics` / `relicsPurchased` | 持有遗物 / 商店买的遗物 |
| `neow_bonus` / `neow_cost` / `neow_bonus_log` | `neowPicked` / `neowSkipped` / `neowBonusLog` | 尼奥选择与开局删牌 |
| `killed_by` | `killedBy` | 死亡遭遇名（死亡率/致死率用） |
| `ascension_level` / `is_daily` / `is_endless` / `is_special_run` | `isA20` / 过滤 | 见第 3 节 |
| （测试 Mod 写）`.tinycat` | `trackedGoldGained` / `trackedFloor0HP` | 金币真实值、第 1 层 HP |

---

# 3. 全局过滤链（决定"分母"是谁）

1. **目录**（`RunHistoryManager.loadData`）
   - 只扫 `runs/` 下的子目录，跳过 `gold_track`。
   - 存档位：`saveSlot=0` 时**排除**名字里含 `0_`/`1_`/`2_` 的目录；`saveSlot>0` 时**只收**含 `{slot}_` 的目录。
   - 只读 `.run` 文件；解析失败（UTF-8/GBK 都不行）静默跳过，不中断批量。
2. **单局有效性**（`Run.valid()`）
   - 必须 `!is_special_run && !is_daily && !is_endless && isA20` → **只统计 A20、非每日/特殊/无尽**。
   - 卡组里出现 `:`（模组卡）、或命中 `bannedCards` → 整局丢弃；遗物含 `:` 同理。
3. **查询条件**（`RunHistoryManager.getRunsInRange`）
   - 日期：`Run.isInRange(start, end)` 按 `timestamp` 的年月。
   - 种子：`SeedFilter`（全部 / 不含种子 / 仅种子）。
   - 放弃局：`includeAbandons=false` 时排除 `abandoned`。

> ⚠️ 提示：任何"总数对不上"的问题，先确认这三层过滤后的 `runCount`，再谈公式。

---

# 4. 指标总表（速查）

| 指标 | 公式 | 分母 | 代码 |
|---|---|---|---|
| 场次/胜率 | `numVictory / (numVictory+numDeath)` | 局数 | `ClassStat` 构造 |
| 最快通关 | `min(playtime)`，**仅心脏通关局** | — | 同上 |
| 连胜 | 连续 `isHeartKill` 的最长长度 | 局序号 | `WinStreak` |
| 卡牌抓率 | `picked / (picked + skipped)` | 被提供次数 | `cardPickStats` |
| 卡组卡牌胜率 | 携带该卡的通关局 / 携带该卡的总张次 | **张次**（同名 2 张算 2） | `deckStats` |
| 尼奥选中次数 | 每个 `(bonus,cost)` 的 `win`（被选中次数） | — | `neowStats` |
| 尼奥选取率 | 被选 / 被提供（**仅 `cost=NONE` 的键可信**） | 被提供次数 | `neowStats` |
| 尼奥胜率 | 选了该项且通关 / 选了该项 | 选择局数 | `neowStats` |
| BOSS 遗物选取率 | 被选 / 被提供（`SKIP` 是其中一个选项） | 被提供次数 | `bossRelicStats` |
| BOSS 遗物胜率 | 选它且通关 / 选它 | 选择局数 | `bossRelicStats` |
| 受到伤害 | `Σdamage / 遭遇次数` | **遭遇次数** | `encounterStats` |
| 战损（实际） | `ΣactualHPLoss / 遭遇次数` | **遭遇次数** | `encounterStats` |
| 每幕总战损 | 每局先 `Σ该幕战损`，再按局平均 | **局数** | `allHPLossStats` |
| 遭遇死亡率 | 死在该遭遇的次数 / 遭遇次数 | 遭遇次数 | `encounterStats` + `killedBy` |
| 地精大块头致死率 | 同上，单独 `nob` 序列 | 遭遇次数 | `nob` |
| 各幕存活率 | 撑过第 n 幕的局 / 到达第 n 幕的局 | 到达局数 | `survivalRatePerAct` |
| 事件胜率 | 遭遇该事件且通关 / 遭遇该事件 | 遭遇次数 | `eventStats` |
| 事件战损 | `Σdamage_taken / 事件次数`（仅 `>0`） | 事件次数 | `eventStats` |
| 遗物胜率 | 持有它且通关 / 持有它 | 持有局数 | `relicStats` |
| 药水使用 | `Σ数量 / 遭遇次数` | 遭遇次数 | `encounterStats` |
| 平均金币 | `Σgold / 局数` | 局数 | `deckSizeStats` |
| 删牌 | 5 类来源求和，空鸟笼 +2 | — | `purgeStats` |
| 火堆/商店行动 | `win`=通关, `loss`=未通关（按行动/物品聚合）；展示用次数与占比 | 该行动出现次数 / 该幕总数 | `campfireActionStats` / `shopActionStats` |
| 火堆偏好（卡牌层，W 主口径） | `win`=该卡被敲次数, `loss`=当时在候选里但没被敲的次数；`percent()`=权重% | 每次锻造「当时未升级的卡牌**种类**数」（同名多张算 1 个） | `campfireUpgradePref*` |
| 火堆偏好（A 替补口径） | `win`=该幕锻造次数, `loss`=累计拥过副本数−锻造次数；`percent()`=最终被敲率 | 到该幕结束累计拥过的副本数（不扣后续移出） | `campfireUpgradeAlt*` |
| 火堆选项权重 | 被选次数 ÷ 该选项**真正可选**的次数；`baseline`=随机期望占比 | 各选项自己的可选次数 | `campfireOptions*` |
| 火堆偏好（统一分母） | 行动占比 = 行动次数 ÷ 访问次数；占锻造 = 升级次数 ÷ 锻造次数；占火堆 = 升级次数 ÷ 访问次数 | `campfireVisits[]` / `campfireSmiths[]` | `campfireActionStats` |

---

# 5. 逐项详解

## 5.1 基础局数、胜率、连胜

- `playTime += playtime`；`fastestTime = min(playtime)`（**只对 `isHeartKill` 生效**）。
- `isHeartKill = victory && damage_taken 里出现过 "The Heart"`。
- `numVictory++` / `numDeath++` 都按 `isHeartKill` 二分类。
- `bestWinStreak`：`WinStreak.getWinStreak(runs, playerClass)`，全角色模式用 `getHighestRotateWinStreak`。
- `totalFloorsClimbed += floorsReached`、`bossKilled += bossesKilled`、`enemyKilled += enemiesKilled`、`highestScore = max(score)`。

> ⚠️ 提示：**"胜率"永远指心脏通关率**。只看 A20，所以"普通模式通关"不会进统计。

## 5.2 卡牌抓率（`cardPickStats`）

```java
Map<Card, Rate<Card>> map = c.floor >= 17 ? cardPicksAfterAct1 : cardPicksAct1;
map.get(c.picked).win++;              // 选了
for (Card skipped : c.skipped) map.get(skipped).loss++;   // 没选
```
- 分幕：`floor < 17` → 第一幕；`>= 17` → 第一幕后（与统计页一致，**不是**用 Boss 楼层动态划分）。
- `SKIP` 是一个真实的 `Card` 选项；颂钵（Singing Bowl）在 `Run` 侧按规则并入，详见 `Run` 构造。
- 卡名统一走 `Card.fromStringIgnoreUpgrades`（去 `+1/+2`）。

> ⚠️ 提示：抓率是**被提供次数的比例**，不是"每局平均抓几张"。

## 5.3 卡组中的卡牌胜率（`deckStats`）

```java
for (Card c : run.masterDeck) {        // 逐张遍历，含同名多张
    Rate rate = cardWinRate.get(Card.fromStringIgnoreUpgrades(c.name));
    if (run.isHeartKill) rate.win++; else rate.loss++;
}
```
> ⚠️ 提示：这里的分母是**张次**。卡组里 2 张打击 → 该卡 win/loss 各加 2。别误读成"局数"。

## 5.4 尼奥（`neowStats`）

- 胜率：只看 `neowPicked`，每局 +1。
- 选取率：`neowPicked` → win++，`neowSkipped` 里每个 → loss++。
- **数据缺口**：`.run` 的 `neow_costs_skipped_log` 全是 `NONE`，带代价的选项收不到「被跳过」计数，
  所以 `(bonus,cost)` 的 `win/(win+loss)` 只对 `cost=NONE` 成立。
- 因此梯度表/统计页改为「一行 = 一个 `(bonus,cost)` 选项」：按 `win`（选中次数）排名，
  占比 = `win ÷ 所有选项的 win 之和`。绝不按 bonus 合并，也绝不把「掉血删2 / 血上限删2」并成「删2」。
- 简称集中在 `tier/NeowShort.java`：代价在前、奖励在后，如 `REMOVE_TWO+PERCENT_DAMAGE → 掉血删2`、
  `TWO_FIFTY_GOLD+CURSE → 诅咒250`、`TWO_FIFTY_GOLD+NO_GOLD → 掉钱250`。

## 5.5 BOSS 遗物（`bossRelicStats`）

- `bossSwapRelic`（尼奥换遗物）单独一条胜率序列 `bossRelicWinRateSwap`。
- `bossRelicChoiceStats[0]` → 第一幕，`[1]` → 第二幕。
- `picked` 为空或 `"SKIP"` → 归为 `SKIP` 选项；否则把 `SKIP` 加进 `skipped`（即跳过也是选项之一）。
- 选中的那个 +win（winRate）；pickRate 上选中 win++、其余 loss++。

## 5.6 受到伤害 / 战损（**最容易误解的一节**）

```java
// ClassStat.encounterStats，对每一次遭遇执行一次
m.get(es.enemies).add(damage);            // 受到伤害 = .run 的 damage_taken[].damage
am.get(es.enemies).add(es.actualHPLoss);  // 战损 = 前一层结束HP - 本层结束HP
```

- `Mean.mean() = Σvalues / values.size()`。
- **同一局打了两次同一遭遇 = 两个样本**，均值为两次之和 ÷ 2。
- `actualHPLoss` 为负表示回血（战后遗物/药水），**保留负值不归零**。
- `damage >= 99999` 时先 `-= 99999`（部分模组/溢出的兼容处理）。
- 跳过：`enemies` 以 `"事件:"` 开头的条目；`getActForStats(floor) == 0`（宝箱层、Boss 后奖励层）。
- 分裂统计：`healing = damage - actualHPLoss`，Ironclad 阈值 6（燃烧之血），其他 0；
  超过阈值 → 进 `有回复` 序列，否则 → `无回复` 序列。

> ⚠️ 提示（口径特性，不是 bug）：`actualHPLoss` 含战后回血，铁甲战士燃烧之血每场抵 6 点，
> 所以低伤害普通怪的战损会是负数；Boss/精英因回血更多，HP 差会比 `damage` 低 8~15。

## 5.7 每幕总战损（`allHPLossStats`）

```java
runAllHPLossSum.merge(act, actualHPLoss, sum);            // 先按局求和
+ eventStats 里 damage_taken > 0 的事件战损
for (act = 1..lastAct) putIfAbsent(act, 0.0);             // 没战损的幕也要记 0
totalAllHPLossPerAct.get(act).add(该局该幕总和);
```
> ⚠️ 提示：这里 `Mean.sampleSize = 局数`（每局每幕一个样本），漏掉 `putIfAbsent` 会让均值偏高——
> 代码里已经处理，改动时别删。

## 5.8 事件（`eventStats`）

- 胜率：每遇到一次该事件 +1（`isHeartKill` → win，否则 loss）。
- 战损：只收 `damage_taken > 0` 的事件，`Mean` 口径是**事件次数**。
- 事件删牌：`event.cards_removed` 每张 +1（归 `PurgeSource.EVENT`）。

## 5.9 死亡率 / 存活率 / 药水

- 死亡率：每次遭遇先 `loss++`（分母=遭遇次数）；若 `killedBy == 该遭遇名`，再 `win++; loss--`。
  于是 `Rate.percent() = 死在这里的次数 / 遭遇次数`。
- `nob`（地精大块头）同样口径，单独一条序列。
- 存活率（`survivalRatePerAct`）：以 `floorsReached` 落在哪一幕判定；心脏通关则四幕全 win；
  `getActForStats == 0` 时**直接 return（不计数）**。
- 药水：`potion_use_per_floor[floor-1].size()`（上限 9），按遭遇加进 `Mean`。

## 5.10 遗物胜率（`relicStats`）

- `relics` → `relicWinRate`：**持有该遗物**的局里通关比例（分母=持有局数）。
- `relicsPurchased` → `relicPurchasedWinRate`：**商店买过它**的局里通关比例。
> ⚠️ 提示：不是"拿了这件遗物后变强多少"，只是相关性；遗物之间会互相混淆。

## 5.11 删牌（`purgeStats`）

| 来源 | 判定 | 归类 |
|---|---|---|
| 商店 | `items_purged[]`（有 `_floors` 时记幕） | `SHOP` |
| 事件 | `event_stats[].cards_removed[]` | `EVENT` |
| 开局 | `neow_bonus_log.cards_removed[]` | `NEOW` |
| 篝火 | `campfire_choices` 里 `key=="PURGE"` 且 `data != null` | `CAMPFIRE` |
| 空鸟笼 | `relics` 含 `Empty Cage` → **手动 +2** | `EMPTY_CAGE` |

- `totalCardsPurged = 五类之和`（`finalise()` 里重算，注意不要与累加值重复）。
- 幕归属用 `getAct(floor)`（**不是** `getActForStats`，删牌不受奖励层影响）。

## 5.12 火堆 / 商店行动

- key 规则：火堆 `SMITH` → 按**卡牌**统计，其余按行动类型（REST/DIG/PURGE…）。
- 每条行动 `isHeartKill ? win++ : loss++`。
- 排序用 `Rate.COUNT_FIRST_COMPARATOR`（次数降序 → 胜率降序 → 名称升序），并在列表首位插入「总计」。

### 火堆偏好（W 主口径 `campfireUpgradePref*` + A 替补 `campfireUpgradeAlt*` + 选项层 `campfireOptions*`）

火堆上的「睡觉 / 抽烟 / 回忆 / 举重 / 挖掘」和「敲哪张卡」是同一次火堆决策的两个层次，分两层统计：

**选项层**：每个选项的 `percent()` = 被选次数 ÷ **该选项真正可选**的次数，不是 ÷ 火堆总访问数。

- `OPTION_KEYS = {SMITH, REST, RECALL, PURGE, LIFT, DIG}`，固定 6 行。
- 可选条件（**只有真的出现在屏幕上的选项才进分母**）：
  睡觉 —— 没被咖啡杯（Coffee Dripper）禁用；升级 —— 没被锤子（Fusion Hammer）禁用，且卡组里还有可升级的卡；
  回忆 —— 1–3 幕且本局尚未回忆；抽烟/举重/挖掘 —— 当时已拥有和平烟斗/吉里亚/铲子（`ownsRelicAt`），举重累计最多 3 次。
  实测 11283 次火堆选择：咖啡杯生效后睡觉 0 次、锤子生效后升级 0 次，说明按楼层判断与游戏一致。
  被禁用的选项若仍算「可选却没选」，会把分母撑大 —— 咖啡杯 142 局、锤子 44 局，不做这一步睡觉/升级的占比会被压低。
- 选项按**被选占比从高到低**排名展示（六个选项是平级决策，不再按固定顺序摆）。
- `baseline` = `100 × Σ(1/当次可选项数) ÷ 可选次数`，即「随机挑一个」的期望占比；`multiple()` = 占比 ÷ baseline。
- **回忆拆「强制 / 提前」**：只有**第三幕**的回忆是必须的——要进第四幕，红钥匙必须在第三幕结束前拿到，
  而第三幕最后一个火堆是最后机会（`campfireRecallForced`）。第一、二幕的回忆是玩家**主动提前拿**，
  本可以留到第三幕，属于选择而不是被迫（`campfireRecallOptional`）。
  实测 1103 局共 1097 次回忆：第一幕 67、第二幕 192、第三幕 838（其中 494 次就在第三幕 Boss 前最后一个火堆）。
  所以「第三幕回忆占比高」不是偏好，是亏损一个火堆资源的固定成本。

**统一榜（`campfireChoiceDist*`，界面/工作簿/梯度/HTML 都用它）**：

「升级」不作为一个条目出现 —— 它就是通往每张牌的入口，拆开后每张可敲的牌各自是一个选项：

- 候选池 `poolSizeSum` = 该次火堆「可敲卡种 + 可用的抽烟/举重/挖掘 + 可用的回忆」（不含睡觉、不含「升级」本身）。
- 每张牌一行：`chosen` = 被敲次数，`visits` = 它出现在候选里的火堆次数。
- 每个行动一行：`chosen` = 被选次数，`visits` = 它真正可选的次数。
- 统一基线 `campfireUpgradeBaseline = 100 ÷ 平均池子大小`，卡牌与行动共用，倍数可同榜比较。
- 睡觉单列（`campfireOptions` 的 REST）：约束是血量，不属于「从这些里挑一个」的竞争，分母是火堆访问次数；
  导出时以 `TierListGroup.note` 挂在每组标题下（界面不占行）。

**卡牌层（W，主口径）**：每次锻造 `SMITH` 是一个样本，逐局重放到那一刻的卡组：

- 分母 = 当时还有未升级副本的卡牌**种类**数（`deck.unupgraded(card) > 0` 且非免疫牌）。
- 分子 = 实际被敲的那一张（同名多张只算 1 个选项：敲第 2 张杂技和敲第 1 张意义相同）。
- 一张卡所有副本都升完就退出分母，不会重复计入；`*` 表示该卡有若干次锻造没能计入（见重建覆盖）。
- `campfireUpgradeBaseline[act] = 100 ÷ 平均候选种类数`，卡片行倍数 = `percent() ÷ baseline`。

**卡牌层（A，替补口径）**：`该幕锻造次数 ÷ 到该幕结束累计拥过的副本数`。

- 只依赖「获得记录」，不依赖当时的卡组快照，所以星盘/空鸟笼删掉哪张都不影响它。
- 局限：它回答的是「这张卡最终被敲了多少」，不是「每次火堆被选中的概率」。
  实测与 W 的排名 Spearman ≈ 0.97，但绝对值系统性高约 20pp（`A ≈ W × 持有期间遇到的火堆数`），
  且对「蛋/War Paint 静默升级」和「不想敲」都给 0%。**排名可以用 A，概率只能用 W。**

**B 口径（÷当时拥有）已剔除**：分子含已被删的副本、分母不含，实测会饱和到 133%/200%；
且火堆抽烟多数没写进 `items_purged`、空鸟笼删牌完全无记录，分母本就偏大。

**重建覆盖与不确定性**（`campfireRebuildOk/Miss`、`campfireUpgradeMissCard`）：

- 逐事件重放来源：初始牌组 + Neow（`neow_bonus_log` 是**驼峰** `cardsObtained/cardsRemoved/cardsTransformed/cardsUpgraded`）
  + 选牌 `card_choices` + 商店 `items_purchased` + 事件 `cards_obtained/cards_removed/cards_transformed/cards_upgraded`
  + 商店删牌 `items_purged` + 火堆 `SMITH/PURGE`。
- 星盘（删 3 变 3）、空鸟笼（删 2）、潘多拉（删光打击防御）没有逐张日志：
  潘多拉可确定处理（删基础牌），星盘/空鸟笼用最终 `master_deck` 做**双向补差**（`DeckEvent.deficit` / `DeckEvent.surplus`）：
  追踪卡组比终局少的补进来（变出的新牌），比终局多的按 boss 层扣掉（被删的牌）。
  不做反向补差时，被删的牌会一直留在候选里，实测把平均候选数撑大约 0.4 张（基线低估约 1pp），**排名不变**。
- 蛋（Molten/Toxic/Frozen）**不产生误差**：`card_choices` 的 `picked`/`not_picked` 带 `+1` 后缀
  （全量 1179 次 picked、12896 次 not_picked），`isUpgradedId()` 据此把获得的牌直接标成已升级，不会进候选。
- 实测 1103 局 / 8765 次锻造里 8741 次（99.7%）能定位；缺失 19 次、已是升级态 5 次，
  这些行在界面标 `*`，以 A 列为准。**W% 是条件概率（被敲÷候选），与分母大小无关**，
  所以星盘/空鸟笼「删了哪 2–3 张」对权重零影响，只让基线微升。
- 剩余已知残差：**War Paint / Whetstone**（179 / 177 局）随机升级 2 张且不记日志，
  被升级的牌升级后仍留在候选里，约占候选的 1.5%（922 次）。这些牌最终形态是 +1，
  但「升级发生在哪一层」不可知，硬修会误伤升级前的有效候选，故不修。
- **不要**把「0% 被敲」当成误差：`Headbutt`（218 次候选、0 次被敲）这类是真的从不被选，
  34 局里 `picked` 全是不带 `+1` 的 `Headbutt`、终局也多是未升级形态。这恰恰是 W 口径能看出来的信息。
- **门槛完全由界面顶部的「样本量」下拉控制（0–10，默认 0 = 不限）**，代码里不再有硬编码下限。
  原 `MIN_UPGRADE_OWNED = 10` 会盖在下拉前面，玩家把下拉调到 0 也只剩候选 ≥10 的卡；
  4.3.7 起改为 0，完全听下拉。导出（xlsx/CSV）跟随同一个下拉值。
- 样本量提示（只写进导出 README，游戏内不显示）：
  `n<10` 看不出方向；`10≤n<30` ±20~30pp；`30≤n<100` ±10~18pp 可排序；`n≥100` ±10pp 以内稳定。
  经验值：单卡候选 30 次能看出量级、100 次排名稳定；分幕看时每幕至少 50 局。
  原理：`SE(p)=sqrt(p(1-p)/n)`，倍数 = p ÷ 基线，p 抖 ±5pp 倍数就抖 ±0.35。
- 排序为第一至第四幕，四幕合计放最后。

## 5.13 金币 / 第 1 层 HP（`Run.parseGold` / `getFloor0HP`）

1. 有 `.tinycat` 且 `gold_gained >= 0` → 用真实值，`goldEstimated=false`。
2. 否则估算：`首层 gold_per_floor − 起始扣除常数 + Σ正差`，有 `MawBank` 再 +12，`goldEstimated=true`。
   - 常数按 `neow_bonus` 查表（`TWO_FIFTY_GOLD=55`、`HUNDRED_GOLD=40`、删牌 40、升级 60…），`neow_cost=NO_GOLD` 时不扣。
3. 第 1 层 HP：`.tinycat` 的 `floor0_hp` 优先，否则按第 1 层伤害反推（Ironclad 减 6），`floor0HPEstimated=true`。
4. `.tinycat` 匹配：取同目录下 `updated_ts` 与 `.run` 结束时间最接近的一条（≤6 小时，且 `start_ts <= 结束时间`）。

> ⚠️ 提示：金币估算的误差几乎全部来自「大额金币层」（金钱雨/事件 +999 之类）和起始金币常数。
> 用 `TinyCatTrackerTest` 跑一局，`runs_test/*.gold.txt` 会直接给出「实际 vs 复算 vs vF 估算」三个数。

## 5.14 Meta 成长（`MetaScaling.addRun`）

逐局取 max：`relics.size / gold / itemsPurged.size / maxHP / maxSearingBlow / potionsCreated / lessonsLearned / maxDagger / maxAlgo`。

## 5.15 怪物危险度梯度表（`tier/MonsterTierBuilder`）

- 只统计 `path_per_floor` 节点为 `M`/`E`/`B` 的遭遇；事件战、第四幕、宝箱层不进表。
- 指标 `actualHPLoss`，**每次遭遇一个样本**（与 5.6 同口径）。
- Boss → `T0`（按均值降序）；非 Boss → 幕内等人数分 `T1..T4`（`base=n/4`，余数给靠前档）。
- 样本参考值：Boss ≥5；非 Boss 单角色 ≥10、全角色汇总 ≥30。**只做标注不做过滤**：
  所有条目都参与 T0-T4 分档，低于参考值的 `lowSample=true`，在图片/HTML 条目右下角显示「样本不足 n=X」、
  xlsx 备注列写「样本不足」。
- 统计量：`mean / 中位数 / P90 / 95%CI = 1.96*std/√n`。
- 名称走 `tier/MonsterNames`，优先级：
  1. **TinyCat 自带汉化包** `location/chinese_english_mapping.json` 的「怪物名称映射」段（社区叫法：六火亡魂 / 乐嘉 / 三柱 / 蟹粥 / 甜圈和八体）；
  2. 游戏内 `monsters.json`（怪物官方名）→ 去空格 → camel → 别名；
  3. `MonsterHelper.getEncounterName`（组队遭遇官方名）；
  4. 英文 ID 兜底。
- ⚠️ **踩过的坑**：`languagePack.getMonsterStrings(id)` / `getEncounterName` 对查不到的 key 会返回
  `"[MISSING_XXX]"` **占位符而不是 null**。任何"取到就 return"的写法都会把占位符当结果输出，
  整条回退链被短路（表现为梯度表里出现 `[MISSING_NAME]`）。取值必须过 `clean()`，含 `[MISSING_` 一律当没找到。
- ⚠️ 注意：TinyCat 自己的 `chinese_english_mapping.json`（685 条）只有卡牌/遗物/术语，**没有怪物名**；
  卡名走 `NameMapper`，怪物名只能走游戏语言包。

## 5.16 导出包结构与图表（`TierListExporter` + `ExportImagePack`）

「全部导出」（`scope=ALL`）产出的包：

```
<包名>/
  data.csv / data.json / tierlist.html / tierlist.png / tierlist.jpg / README.txt
  总体统计.xlsx … 火堆偏好.xlsx（13 个）
  怪物危险度.xlsx                  ← 仅 ALL 才有
  images/{cards,relics,monsters}/  ← 素材
  图表/                            ← 渲染好的图（仅 ALL 才有完整版）
    00-完整梯度图.png/.jpg         ← 卡牌/Boss遗物 + 怪物危险度，一张图
    01-第一幕卡牌选取率.png/.jpg
    02-第一幕后卡牌选取率.png/.jpg
    03-BOSS遗物选取率.png/.jpg
    04-怪物危险度-第一幕.png/.jpg
    05-怪物危险度-第二幕.png/.jpg
    06-怪物危险度-第三幕.png/.jpg
```

- `TierListImageRenderer` / `MonsterTierImageRenderer` 都拆成了 `sectionHeight(groups)` + `drawSection(g, …, height)`，
  以便「分图」只画一个分组、「完整图」用一张大画布 `translate` 逐段画（避免中间副本占内存）。
- 根目录的 `tierlist.png/jpg` **只有卡牌/Boss 遗物**（历史兼容，HTML 也链接它）；要看含怪物的整图用 `图表/00-*`。
- `MonsterTierExporter.writeInto()` 返回 `MonsterTierModel`，就是给 `ExportImagePack` 复用的。
- 卡牌图走 `TierImageExporter.resolveCard()`：**不能直接用 `CardLibrary.getCard(显示名)`**，
  官方重命名过一批卡（ID 仍是旧名：Gash→Claw、Undo→Equilibrium、Redo→Recursion、Lockon→Bullseye、
  Steam→Steam Barrier、Steam Power→Overclock、Conserve Battery→Charge Battery），
  直接按显示名查会得到 null → 占位图。
  解析顺序：① 按 ID 直查 → ② 英文卡名索引 → ③ 内置 `LEGACY_CARD_IDS` 兜底 → ④ 当前语言的
  `name`/`originalName`/`cardID` 索引。
  **② 是关键**：游戏语言为中文时 `AbstractCard.name` 就是中文（"均衡"），拿它建索引永远匹配不到
  `.run` 里的英文名（4.2.2 就是这么失败的），所以英文名要从游戏自带 `localization/eng/cards.json`
  读（key=cardID、NAME=英文名，与客户端语言无关）。同名不同卡（各色 Strike/Defend）标记为歧义、不入索引。

> ⚠️ 提示：新增一张图 = 在 `ExportImagePack.write()` 里加一次 `writePair(...)`；
> 改版式只动两个 Renderer 的 `drawSection`，不要在 `TierListExporter` 里写绘制代码。

## 排名型梯度（火堆 / 商店 / 尼奥 / 火堆偏好，4.3.0 起，4.3.1 改分档）

抓率型（卡牌 / BOSS 遗物）用固定阈值 `Tier.of()`；排名型不能用，因为指标形态完全不同：

| 表 | 指标 | 分母 | 实测量级 |
|---|---|---|---|
| 火堆 · 每幕行动 | 次数 ÷ 该幕火堆行动总数 | `campfireActionsActN[0]`（火堆总数行） | 头名 24.8%，第 8 名 3.0% |
| 火堆 · 行动类型 | 次数 ÷ 四幕合计 | 各幕总数之和 | 头名 9.5%，最小 0.6% |
| 商店 · 购买商品 | 次数 ÷ 该幕购买总次数（剔除删牌/汇总行） | 商品计数之和 | 头名 10.6% |
| 火堆偏好 | 该幕被敲次数 ÷ 当时未升级卡牌种类数（倍数 = ÷ 随机基线） | 每次锻造各自的候选种类数 | 头名约 88%，基线 13–16%，倍数 2–6× |
| 尼奥 · 开局选项 | 选中次数 ÷ 尼奥选择总次数 | 所有 `(bonus,cost)` 的 `win` 之和 | 头名约 10%，尾部 <1% |

同一套 90/75/60 阈值套上去：火堆会全挤在 F，尼奥会全进 S，所以分档改走 `tier/RankRule`：

- `QUARTILE`（等人数四分位）：组内按指标降序，前 25% = S、25-50% = A、50-75% = B、其余 = C，
  同值同档随意但条目数固定，任何条目数（5~37 条）都能给出 4 个有效档；和「怪物危险度」T1-T4 同一套思路。
- `CUMULATIVE`（累计占比）：S = 累计覆盖 50% 的条目、A 到 75%、B 到 90%、其余 C。
  头部极值友好（火堆 S 只剩 2 张），但条目少或长尾长时会塌缩——5 条的「行动类型」实测 S=1/B=1/C=3，没有 A。
- `RELATIVE`（相对最大值）：≥50% 最大值 = S、25% = A、10% = B、3% = C、其余 D。
  跨指标可比，但长尾全进 D（商店第一幕 15/30 条在 D），信息量低。

档位枚举是 `Tier.RS/RA/RB/RC/RD`（表内只显示字母），与抓率档 `S..F/0` 互斥；
`TierListExporter.tiersFor(scope)` 决定一张表渲染哪一套，两套不会混在同一张表里。
**`JENKS`（当前 ACTIVE，k=5 + 低于 1% 独立档）**：先按**不同数值**分组（同值必同档），再在分组上做加权平方和 DP，切成 5 段（S/A/B/C/D），使各档内部离差平方和之和最小；**另把指标 < 1%（含 0%，即从未被选）单独切成最低档 `R0`，不参与自然断点**——长尾表里这批条目很多，和 1%~7% 一起切会被并成一档，看不出「几乎不会被选」和「偶尔被选」的差别。实测 32 张表：档内跨度中位 1.50（等人数 2.29）、空档 0（数值四分位 5）、80% 重采样稳定率 火堆 86.4% / 商店 80.9%。`best[0][i]` 必须置无穷，否则退化成「首档 1 条、其余全进末档」。

**`VALUE_QUARTILE`（4.3.2 短暂使用）**：直接对指标数值取 Q1/Q2/Q3（inclusive 插值）当切点，≥Q3=S、Q2~Q3=A、Q1~Q2=B、其余=C。并列条目必定同档，各档人数不等。火堆第一幕实测：Q3=26.5、Q2=5.5、Q1=2.0 → S11/A11/B12/C10。坑：次数是正整数，Q1 常等于最小值 1，此时 C 档为空（32 张表 17 张如此）。

**`HYBRID`（4.3.1 短暂使用）**：先找断层——指标 ≥ 头名 50% 的条目全部进 S，其余按人数三等分 A/B/C。
火堆第一幕实测：等人数 S=11（24.8%~2.5%，跨 10 倍）→ HYBRID S=2（双重释放 24.8%、电击 21.9%）；
商店这种无断层的表仍按人数均分。代价是各档人数不再相等（火堆 S 1~6 条、商店 S 2~14 条）。

**商店删牌必须单开**：删牌约 4.5 次/局、1.00 次/店，占商店全部动作 55%；
如果混进「购买商品」表，删牌一个条目就占 55%，其余商品只能分剩下的 45%，会全被压进 C 档。
所以 `buildShop` 额外产出一张「商店动作（四幕合计）」：删牌 / 买卡 / 买遗物 / 买药水。

换规则只改 `RankRule.ACTIVE` 一行，其它代码不用动。

**火堆每幕表必须同时含卡牌与行动**：SMITH 按升级目标卡统计，睡觉/回忆/删牌/挖掘/举重按行动类型统计，
两类都进同一张每幕表。早期版本只把卡牌放进每幕、把 PURGE/RECALL 踢到「四幕合计」，
等于把删牌和回忆从每幕行动里删掉了——这是错的。升级目标卡另附「抓取 X/Y」上下文，
因为升级次数受持有量影响（神化只升 7 次通常是没有抓到，不是不升级）。

**注意火堆/商店页上那个 100% 不是胜率**：ClassStat 里这两个维度的 `Rate.win` 记的是「通关局里的出现次数」、
`loss` 是「失败局里的出现次数」；当样本全是通关局时页面会显示 `次数/次数 = 100%`。
统计页与导出表一律改用「次数 ÷ 该幕总数」，不再把 `win/total` 当行动占比。

尼奥的坑另记：`.run` 的 `neow_bonuses_skipped_log` 不带代价（`neow_costs_skipped_log` 全是 NONE），
带代价的选项永远收不到「被跳过」计数，`win/(win+loss)` 虚高到 100%。
导出与页面改为「按选中次数排名 + 选中次数 ÷ 全部尼奥选择次数」，一行一个 `(bonus,cost)`、绝不按 bonus 合并。
统计层 `ClassStat.neowStats` 的键没改（改了属于口径变更，要先在 ALGORITHM-AUDIT 留痕）。



---

# 6. 口径特性 vs 真 bug（判别表）

| 现象 | 是不是 bug | 说明 |
|---|---|---|
| 战士普通怪战损为负 | 不是 | `actualHPLoss` 含燃烧之血 +6 |
| 梯度表 T4 全是负数/接近 0 | 不是 | 同上，低伤害遭遇被回血抵消 |
| 每幕总战损 ≠ 该幕各遭遇战损之和 | 可能不是 | 总战损另计事件战损，且分母是局数 |
| 卡牌胜率分母比局数大 | 不是 | 按张次统计（5.3） |
| 抓率里的 SKIP 占比很高 | 不是 | SKIP 是合法选项 |
| 胜率与游戏内"通关"不一致 | 不是 | 只认心脏通关 + A20 |
| 某局完全没进统计 | 多半不是 | 被 `valid()` 过滤（非 A20/每日/模组卡） |
| 金币数偏高 30%+ | 是（已知） | `gold_per_floor` 估算的常数误差，见 5.13 |

---

# 7. 改代码协议（AI 必读）

**改之前**
1. 读本文件对应小节 + `docs/DATA-FLOW.md`。
2. 用真实 `.run`（`runs/` 里找同类局）+ `scripts/validate_gold_estimate.py` / `check_hp_metric.py` 验证新口径。
3. 确认改的是「聚合」还是「展示」：口径改动只能落在 `stats/`，展示改动只能落在 `ui/` `patches/` `tier/`。

**改之后（必须全做）**
1. 新增维度要同步四处：采集（`Run`）→ 聚合（`ClassStat`）→ 渲染（`StatsScreenPatch`/`CharStatRenderer`）→ 本地化（`LocalizationKeys` + 两份 `strings*.json`）。
2. 更新本文件的对应小节 + 在 `docs/ALGORITHM-AUDIT.md` 的变更留痕表加一行。
3. 回复用户时**明确列出改了哪些文件**，并说明是否影响统计口径。
4. 编译：`build-local.bat` 或 `gradle buildJAR`（必须 JDK 8）。

**禁止**
- 不要在 `tier/` 或 `ui/` 里偷偷改统计公式（会绕过口径审计）。
- 不要引入第三方依赖（POI/JUnit 等），Excel 用 `XlsxWriter`。
- 不要改 `.tinycat` 的键名（主 Mod 只读，兼容性是硬约束）。
- 不要用 Java 9+ 语法（`var`、`Stream.toList()`、文本块）。

---

# 8. 自检命令

```bash
# 装了哪些版本
ls "C:/Program Files (x86)/Steam/steamapps/common/SlayTheSpire/mods"

# 核心统计是否被改（空 = 没动）
git diff HEAD -- src/main/java/tinycat/stats/

# jar 与源码是否一致
md5sum "C:/Program Files (x86)/Steam/steamapps/common/SlayTheSpire/mods/TinyCat_V4.2.2.jar" build/libs/TinyCat_V4.2.2.jar

# 校验导出包（用 runs/*.run 独立复算后逐项比对）
python scripts/verify_export.py "<导出目录>" --class DEFECT

# 与历史 jar 逐 class 比对（改动面审计）
python - <<'EOF'
import zipfile, hashlib
a = zipfile.ZipFile('mods/TinyCat_V4.2.2.jar'); b = zipfile.ZipFile('mods-backup/TinyCat_V4.2.2_public.jar')
na = {n for n in a.namelist() if n.endswith('.class')}; nb = {n for n in b.namelist() if n.endswith('.class')}
print('changed:', [n for n in sorted(na & nb) if hashlib.md5(a.read(n)).digest() != hashlib.md5(b.read(n)).digest()])
print('added:', sorted(na - nb), 'removed:', sorted(nb - na))
EOF
```

---

# 9. 常见坑（踩过的）

1. `Mean.mean()` 只缓存最后一次 size 的结果，**不要在遍历中途反复读 mean**。
2. `Rate.percent()` 在 `total=0` 时返回 0（`Utils.calculatePercent`），所以新选项不会显示 NaN。
3. `getAct()` 与 `getActForStats()` 不同：前者含奖励层，后者在宝箱层/Boss 后一层返回 0。删牌用前者，战损用后者。
4. A20 第三幕是**双 Boss**（第 50、51 层），`captureBossFloors` 对第三幕取 `max`，不要改成 `min`。
5. `.run` 可能是 GBK，读文件必须 UTF-8 → GBK 兜底。
6. `cardChoices` 的 floor 分幕用 17，与战损的幕划分是**两套逻辑**，不要统一。
7. 导出里的「全部导出」= `TierListScope.ALL`，会额外并入 `怪物危险度.xlsx`；单页导出不会。
8. `MonsterTierExporter.MOD_VERSION` 是硬编码字符串，改版本号时别忘了。

> **导出主标**：火堆/商店/尼奥的大数字都写**原始次数**（`351 次`），副标写 `24.81% · 351/1415`。
> 分档在次数上切，主标必须与分档同源；同表内占比与次数等比，档位一致。
> 尼奥的分母固定为「全部尼奥选择次数」，不是提供次数（见 5.4 的数据缺口）。
