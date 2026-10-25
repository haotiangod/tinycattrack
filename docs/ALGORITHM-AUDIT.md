# TinyCat 算法审计与变更留痕

> 最近更新：2026-10-23 20:55
> **口径定义的唯一权威文件是 `docs/CORE-LOGIC.md`**（所有指标的公式/分母/代码位置/坑）。
> 本文件只做两件事：① 记录「有没有被改过」的审计证据；② 按日期留痕每次改动。
> 任何改动统计口径的提交，都必须在下表补一行。

---

## 0. 结论（2026-10-23 复核）

**本次会话没有改动任何统计算法。** 证据三条：

1. `git diff HEAD -- src/main/java/tinycat/stats/ src/main/java/tinycat/RunHistoryManager.java src/main/java/tinycat/Utils.java src/main/java/tinycat/patches/StatsScreenPatch.java` **输出为空**。
   `Run.java`、`ClassStat.java`、`EncounterStats.java`、`Mean.java`、`Rate.java` 全部未改。
2. 当前装的 `mods/TinyCat_V4.2.2.jar` 与改动前（14:44 的 `mods-backup/TinyCat_V4.2.2_public.jar`）逐字节比对：
   94 个 class 里只有 **8 个**不同，全部位于 `tinycat/tier/`（导出层），新增 1 个 `MonsterNames`，
   **0 个删除、0 个资源差异**。`stats/*`、`patches/*`、`ui/*` 的 class 哈希完全一致。
3. `MonsterTierBuilder.java` 的最近修改时间是 **15:22**（早于本次会话 20:13 的首次扫描），
   且 diff 只有两处：删掉手写译名表 `NAME_ZH`、把显示名改成调用 `MonsterNames.displayName()`。
   `collect()` / `mean()` / `stddev()` / `percentile()` / `appendNonBossTiers()` 一行未动。

---

## 1. 口径：受到伤害 / 战损 = 每次遭遇的平均值

**这是原始设计，没有被改过。** 语义就是「同一局遭遇两次 → 两个样本，加和除以次数」。

### 数据来源

`.run` 的 `damage_taken[]` 每打一场战斗就追加一条：`{floor, enemies, damage, turns}`。
`Run` 构造时为每条生成一个 `EncounterStats`（`Run.java`）：

| 字段 | 含义 | 来源 |
|---|---|---|
| `damage` | 本场战斗实际掉的血（游戏内 `damage_taken[].damage`） | `.run` 原始字段 |
| `actualHPLoss` | **进入本层前 HP − 本层结束 HP**（负数=回血，不归零） | `current_hp_per_floor[floor-2] - current_hp_per_floor[floor-1]`；第 1 层用 `floor0_hp`（`.tinycat` 或估算） |

### 聚合（`ClassStat.encounterStats`，按「幕 × 遭遇名」）

```java
m.putIfAbsent(es.enemies, new Mean(es.enemies));
m.get(es.enemies).add(damage);          // 受到伤害
am.get(es.enemies).add(es.actualHPLoss); // 战损
```

`Mean.mean()` = `Σvalues / values.size()`。
**每发生一次遭遇 add 一次**，所以一局里打了两次 `3 Darklings` 就是两个样本，均值 = 两次之和 ÷ 2。
统计页和导出里的「平均受到伤害 / 平均战损」都是这个 `mean`。

> 注意区分：**每幕战斗战损总计** 用的是另一种口径 —— 每局每幕先求 `ΣactualHPLoss`，
> 再按局求平均（`runCombatSum` 一次/局/幕，`Mean.sampleSize = 局数`）。
> 这是原代码的设计（用于「一局总共掉多少血」），不是本次改动引入的。

### 分裂统计（有回复 / 无回复）

`healing = damage - actualHPLoss`；Ironclad 阈值 6（燃烧之血），其他角色 0。
超过阈值算「有回复」，否则算「无回复」，两组各自用同一个 `Mean` 口径。

---

## 2. 幕划分与跳过规则（`Run.java`）

- `captureBossFloors()`：从 `damage_taken` 里识别 Boss 楼层。第一幕取 `min`，
  第二幕取 `min`，第三幕取 **`max`**（A20 双 Boss，第 50、51 层两场都要算进第三幕）。
- `getAct(floor)`：以 Boss 楼层为边界；无 Boss 数据时回退 `17 / 34 / 52`。
- `isRewardFloor(floor)`：宝箱层（`path_per_floor == "T"`）以及每个 Boss 后一层 → 返回 `true`。
- `getActForStats(floor)`：`isRewardFloor ? 0 : getAct(floor)`，返回 0 的楼层在统计里直接跳过。
- `getPathNode(floor)`：`path_per_floor[floor-1]`，取值 `M/E/B/?/$/R/T`；空串=无记录。

---

## 3. 怪物危险度梯度表（`MonsterTierBuilder`）

- 只统计 `path_per_floor` 节点为 `M`（普通）/ `E`（精英）/ `B`（Boss）的遭遇；
  事件战（`?`）、第四幕固定战、宝箱层一律不进表。
- 指标：`EncounterStats.actualHPLoss`，**每发生一次算一个样本**（与第 1 节同一口径）。
- 分档：Boss 全部进 `T0` 按均值降序；非 Boss 按幕内「等人数」分成 `T1..T4`
  （`base = n/4`，余数给靠前档位）。
- 样本门槛：Boss ≥5、单角色非 Boss ≥10、全角色汇总非 Boss ≥30；不达标进「低样本附录」，不分档。
- 均值/中位数/P90/95%CI 全部基于该遭遇名自己的样本数组
  （`mean()`、`percentile()`、`1.96*std/√n`）。
- 已知口径特性（非 bug）：`actualHPLoss` 含战后回血，铁甲战士的燃烧之血每场抵 6 点，
  所以低伤害普通怪会出现负「战损」。

---

## 4. 金币（`Run.parseGold`）

1. 优先 `.tinycat` 的 `gold_gained`（`TinyCatTrackerTest` 写入，`goldEstimated=false`）。
2. 否则用 `gold_per_floor` 估算：`首层值 − 起始扣除常数 + Σ正差`，
   常数按 `neow_bonus` 查表（两项金币 55、一项金币 40、删牌 40…），有 MawBank 再 +12，
   并置 `goldEstimated=true`（UI/CSV 必须标注）。
3. 第 1 层 HP：优先 `.tinycat` 的 `floor0_hp`；否则按第 1 层伤害反推，`floor0HPEstimated=true`。

---

## 5. 变更留痕

| 日期 | 文件 | 改动 | 影响统计口径？ |
|---|---|---|---|
| 2026-10-25 18:22 | `stats/ClassStat.java`、`tier/NeowRanker.java`(新)、`tier/AllStatsExporter.java` | ① 新增尼奥两两对决矩阵 `neowBeats`（每局 1 选 + 3 跳 = 3 场对决）；② 新增 `NeowRanker`：Bradley-Terry 标准化率 + Wilson 区间（简单率）+ bootstrap 100 次的区间（标准化率）；③ 尼奥工作簿拆成「简单率」「标准化率」两张表，各带 95%CI 与名次变动 | 是（新增标准化口径，简单率保持不变） |
| 2026-10-25 17:26 | `patches/StatsScreenPatch.java`、`tier/TierListBuilder.java` | **更正 17:06/17:09 的错误结论**：`neow_costs_skipped_log` 并非全为 NONE（全量 1026 局里有 562 条带代价，37 个组合只有 5 个是 100%），代价信息基本完整。游戏内尼奥页分母由「总局数」改为「该选项被选中+被跳过」，并按选取率排序；删除上一轮加的「被跳过的代价未记录」错误备注 | 是（尼奥分母） |
| 2026-10-25 17:26 | `tier/TierListBuilder.java` | **撤销 17:06 的「尼奥按 bonus 聚合」**：不同代价是不同选项，不允许因效果相同而合并，恢复 `(bonus, cost)` 逐项统计。带代价的条目在 `context` 标注「被跳过的代价未记录，分母仅含被选中次数」。同时修复一次误删：替换该方法时连带删掉了 `safe/whatOf/countOf/totalOf/pickRatesByName/isShopMeta/shopNameZh/RankItem`，已全部补回 | 是（撤回上一轮口径变更） |
| 2026-10-25 17:06 | `tier/TierListBuilder.java`、`tier/TierImageExporter.java` | ① **尼奥改按 bonus 聚合**：`.run` 的 `neow_costs_skipped_log` 对被跳过选项一律写 NONE，导致 (bonus,cost) 粒度下 `cost != NONE` 的组合分母只剩「被选中」次数、必然 100%；② 新增药水图片导出 `exportPotion`（`PotionHelper.getPotion(id)` + 反射取 `img`），输出 `images/potions/`；`classifyShopItem` 加 `endsWith("Potion")` 兜底 | 是（①尼奥分母） |
| 2026-10-25 16:51 | `patches/StatsScreenPatch.java`、`tier/TierListBuilder.java` | ① 火堆行动页、商店行动页的条目改回显示**次数**（原为「占比 (n/N)」），只动这两个前端；② `shopNameZh` 末尾由 `return name` 改为 `return NameMapper.toChinese(name)`：药水在 .run 里是 ID（`SwiftPotion`），`PotionHelper` 查不到时原来直接吐英文，明明映射表有「抽牌药」却没被使用 | 否，展示与汉化回退 |
| 2026-10-25 16:33 | `tier/TierListBuilder.java` | 商店梯度图的样本量门槛改按「购买次数」（picked）过滤，不再用 offered——商店每条 offered 都是该幕购买总数（统一值），拿它比 minSample 等于没过滤（选 4 时买过 1 次的条目照样出现）。删牌条目同样加门槛 | 是，条目按下拉门槛筛选 |
| 2026-10-25 16:28 | `tier/TierListExporter.java`、`patches/StatsScreenPatch.java`、`location/chinese_english_mapping.json` | ① `MOD_VERSION` 从硬编码 `4.2.2` 改为运行时读 `ModTheSpire.json`（导出页/README 版本号一直显示旧版，误导排查）；② 商店「有效商店数」只显示百分比，去掉 `(107/111)`；③ 从 `desktop-1.0.jar` 的 `localization/zhs/cards.json` 补 12 个卡名官方汉化（幽魂形态/炼制药水/安宁/怒火中烧/致残毒云/先见之明/蒸汽护壁/猛虎下山/尖啸/全力攻击/隐秘打击/计划妥当） | 否，展示与版本号 |
| 2026-10-25 16:06 | `tier/TierListBuilder.java`、`tier/TierImageExporter.java`、`stats/ClassStat.java` | ① 尼奥选项：分母从「总局数」改为「该选项被提供次数」（选中+`neow_bonuses_skipped_log` 跳过），并按选取率排序（原来按次数）；② 商店分类 `classifyShopItem` 改为**药水优先**，并排除 `RelicLibrary.getRelic` 对未知 key 返回 Circlet（头环）的兜底——此前所有药水都被判成遗物、中文名显示「头环」；③ `items_purchased` 里药水名两种写法（`PowerPotion` / `Swift Potion`）归一化为 ID | 是（①分母、②③条目归类） |
| 2026-10-25 02:05 | `tier/Tier.java` | `isRankBased()` 漏了 `RZ`（<1% 档），导致该档有数据但「梯度图/HTML/CSV」遍历档位时整档被跳过；补上后导出恢复完整 6 档 | 否，只影响渲染档位 |
| 2026-10-25 01:48 | `tier/TierListGroup.java`、`tier/TierListBuilder.java`、`tier/TierListExporter.java`、`tier/TierListImageRenderer.java` | 梯度图/HTML/CSV 也改用统一榜 `campfireChoiceDist*`（遗物行动 `type=action` + 像素文字占位 TOKE/LIFT/DIG/RECALL）；`TierListGroup` 新增 `note`，把「睡觉 X% (n/N)」挂在每组标题下（HTML + 图片） | 是，导出榜单从只含卡牌改为含遗物行动 |
| 2026-10-25 01:37 | `patches/StatsScreenPatch.java`、`tier/TierListExporter.java` | ① 遗物行动不再用 `[方括号]` 占位：游戏 bitmap 字体无 `[` 字形，整段（含名称）会被吞掉，改为直接显示名称；② 睡觉行的「单列，不进竞争池」说明从界面移除，改写到导出 README | 否，纯展示 |
| 2026-10-25 01:35 | `stats/ClassStat.java`、`patches/StatsScreenPatch.java`、`tier/AllStatsExporter.java` | ① **口径变更**：新增统一榜 `campfireChoiceDist*`，把「升级」拆回每张可敲的牌，与抽烟/举重/挖掘/回忆同榜按占比排序；睡觉单列（约束是血量）。新增 `poolSizeSum`，基线改为 `100 ÷ 平均池子（卡种+可用遗物行动+可用回忆）`。② 内部类 `CampfireChoice` 改名 `CampfireShare`：原名与 `com.megacrit...CampfireChoice` 冲突，遮蔽 import 导致 `choice.floor` 解析失败。③ 补上只有调用没有实现的 `buildChoiceDist` | 是，基线分母从「卡种数」改为「池子大小」 |
| 2026-10-25 01:04 | `patches/StatsScreenPatch.java` | 火堆选项行格式与卡牌行统一：`名称 占比 倍数 (被选/可选)`，倍数用选项层基线（100÷平均可选项数），颜色同职业色 | 否，纯展示 |
| 2026-10-25 00:57 | `stats/ClassStat.java`、`patches/StatsScreenPatch.java` | ① 选项可选条件补全：咖啡杯（Coffee Dripper）禁用睡觉、锤子（Fusion Hammer）禁用升级，被禁用时不进分母（原来 REST 恒 true、SMITH 只看 hasUpgradable，把禁用选项算成「可选却没选」）；② 六个选项改为按被选占比降序展示 | 是（①分母变小，睡觉/升级占比略升） |
| 2026-10-25 00:32 | `tier/TierListExporter.java`、`patches/StatsScreenPatch.java`、`tier/TierListBuilder.java` | ① 导出 README 新增「算法口径」与「样本量建议」两段（含 n<10 / 10-30 / 30-100 / ≥100 的置信区间表），游戏内不显示；② 单卡硬编码门槛 10 → **0**：原值会盖在界面「样本量」下拉（0–10，默认 0=不限）前面，玩家调到 0 也只剩候选 ≥10 的卡；改 0 后门槛完全交给下拉，导出跟随同一值 | 是，条目按下拉筛选，默认全量 |
| 2026-10-25 00:25 | `stats/ClassStat.java`、`patches/StatsScreenPatch.java`、`tier/RankRule.java`、`tier/Tier.java`、`tier/TierListExporter.java` | ① 回忆「强制」只认第三幕（前两幕标「提前」）；② 选项层压成单行显示、去掉逐项倍数；③ 卡牌行改「权重% + N倍 + (被敲/候选)」并加倍数读法说明；④ 分档把 <1%（含 0%）切成独立最低档 `RZ`（共 6 档）；⑤ HTML 卡片 `.cnt` 单行省略、`.stat` 限宽 46% | 是（①口径、④分档） |
| 2026-10-25 00:00 | `tier/TierListImageRenderer.java`、`tier/TierListExporter.java`、`tier/TierListBuilder.java` | 导出文案从旧口径（升级次数/拥有张数）改为 W 口径：图片副标题与注释、HTML 口径说明/计数标签/表头、CSV 表头、导出 README、梯度构建注释。CSV 保持 14 列不变（不新增列，避免数据行错位），完整列只在 xlsx 里 | 否，纯文案 |
| 2026-10-24 23:55 | `stats/ClassStat.java` | 新增 `KIND_SURPLUS` / `DeckEvent.surplus` / `ORDER_SURPLUS`：星盘·空鸟笼·潘多拉删掉的牌按 boss 层反向补差扣掉（原来只补加不扣删，被删的牌会一直留在候选里）。实测平均候选数 −0.4 张、基线 +1pp，**排名不变** | 是，分母略降（更准） |
| 2026-10-24 23:40 | `stats/ClassStat.java` | 火堆偏好改为 **W 主口径**（每次锻造 ÷ 当时未升级卡牌种类数，同名去重）+ **A 替补**（`campfireUpgradeAlt*`，锻造次数 ÷ 累计拥过副本数）；新增 `campfireRebuildOk/Miss`、`campfireUpgradeMissCard`、`campfireAstrolabeRuns/EmptyCageRuns`；**剔除 B 口径**（÷当时拥有，会饱和 >100%） | 是，卡牌层分母从「拥有张数」改为「当时候选种类数」 |
| 2026-10-24 23:40 | `patches/StatsScreenPatch.java` | 火堆偏好页拆三段：概览（平均候选 / 随机基线 / 重建覆盖）+ 选项权重 6 行（附随机基线、倍数、回忆强制/非强制）+ 卡牌权重（倍数 / 权重% / 被敲/候选，缺失标 `*`）；删除旧 `renderCampfireMix` | 否，只改展示 |
| 2026-10-24 23:40 | `tier/AllStatsExporter.java` | 火堆偏好表列改为 候选次数/被敲/权重%/随机基线%/倍数/A升级率%/备注；新增「火堆选项」工作表（6 选项 × 5 幕） | 否，导出跟随口径 |
| 2026-10-23 15:22 | `tier/MonsterTierBuilder.java` | 删除手写译名表 `NAME_ZH`，显示名改走 `MonsterNames`（**本次会话之前就已存在**） | 否，只影响显示名 |
| 2026-10-23 20:18 | `tier/MonsterTierExporter.java` | 新增 `writeInto(screen, root)`，供「全部导出」复用 | 否，导出编排 |
| 2026-10-23 20:18 | `tier/TierListExporter.java` | `scope=ALL` 时把 `怪物危险度.xlsx` + `images/monsters` 并入同一个包；`STAT_FILES` 加该文件 | 否，导出编排 |
| 2026-10-23 20:48 | `tier/MonsterNames.java` | 重写：游戏内 `monsters.json` → camel → 别名 → `MonsterHelper.getEncounterName` → 英文 | 否，只影响显示名 |
| 2026-10-23 20:43 | `tracker-test/**` | 测试 Mod 0.2.0：写 `runs_test/` 校验表，`goldGained` vs `gold_per_floor` 复算差额 | 否，独立测试 Mod |
| 2026-10-23 21:00 | `docs/CORE-LOGIC.md` | 新建：全指标口径说明书（AI 上手指南） | 否，纯文档 |
| 2026-10-23 21:14 | `tier/ExportImagePack.java`（新增）、`TierListImageRenderer`、`MonsterTierImageRenderer`、`TierListExporter`、`MonsterTierExporter` | 导出包新增 `图表/`：`00-完整梯度图`（卡牌+BOSS遗物+怪物危险度拼一张）+ 按指标页分图；两个渲染器拆出 `sectionHeight/drawSection` 供拼接复用；`writeInto` 返回模型 | 否，纯导出/渲染 |
| 2026-10-24 11:09 | `tier/TierImageExporter.java`、`docs/HANDOVER.md`（新增） | 修 7 张卡牌图缺失：卡牌 ID ≠ 显示名，改为按 `name/originalName/cardID` 建名称索引解析；新增交接文档 | 否，仅图片解析 |
| 2026-10-23 22:38 | `ui/MoreStatsScreen.java`、`scripts/verify_export.py`（新增） | 修 bug：`classStats[4]`（全角色汇总）被按当前选中角色做别名，导致导出的「全角色汇总」行等于当前角色；新增独立校验脚本（原始 .run 复算 vs 导出包） | 是（导出汇总行） |
| 2026-10-23 21:58 | `tier/MonsterNames.java`、`MonsterTierBuilder/Entry/Group/Exporter/ImageRenderer` | ① 名称优先读 TinyCat 自带汉化包（`怪物名称映射` 段，57/57 命中）；② 去掉样本下限：全部参与分档，低于参考值的标 `lowSample` 并在图片/HTML 右下角、xlsx 备注列标注「样本不足」 | 是，分档结果变化（低样本条目从附录进入 T1-T4） |
| 2026-10-23 21:37 | `tier/MonsterNames.java` | 修 `[MISSING_NAME]`：游戏语言包查不到 key 时返回占位符而非 null，旧写法直接返回导致 40+ 遭遇显示占位符；新增 `clean()` 过滤 `[MISSING_` | 否，只影响显示名 |

**没有任何一行改动落在** `stats/`、`patches/`、`RunHistoryManager`、`tier/MonsterTierBuilder` 的计算方法里。

---

## 6. 怎么自己复核

```bash
# 1) 核心统计是否被改
git diff HEAD -- src/main/java/tinycat/stats/

# 2) 装的 jar 与 build 是否一致
md5sum "C:/Program Files (x86)/Steam/steamapps/common/SlayTheSpire/mods/TinyCat_V4.2.2.jar" \
       build/libs/TinyCat_V4.2.2.jar

# 3) 与改动前的 jar 逐 class 比对（需 Python）
python - <<'EOF'
import zipfile, hashlib
a = zipfile.ZipFile('mods/TinyCat_V4.2.2.jar')
b = zipfile.ZipFile('mods-backup/TinyCat_V4.2.2_public.jar')
na = {n for n in a.namelist() if n.endswith('.class')}
nb = {n for n in b.namelist() if n.endswith('.class')}
for n in sorted(na & nb):
    if hashlib.md5(a.read(n)).digest() != hashlib.md5(b.read(n)).digest():
        print('changed:', n)
print('added:', sorted(na - nb))
EOF
```
