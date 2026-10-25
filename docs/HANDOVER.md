---
title: TinyCat 交接文档（HANDOVER）
summary: 一页看懂现状、产物、口径入口、本轮修了什么、还有哪些坑与待办
read_when:
  - 接手这个项目 / 换会话继续干
  - 不确定某个数字或某张图是怎么来的
canonical_for: 交接与现状
last_updated: 2026-10-24 16:30
---

# TinyCat 交接文档

《杀戮尖塔》本地数据统计 Mod（Java 8 + Gradle 8.0 + ModTheSpire）。数据全部来自本地
`runs/**/*.run`（RunHistoryPlus 产出），不联网、不上传。

## 1. 三十秒上手

```bash
cd C:/Users/MI/Desktop/project/tinycat

# 普通版（不带图，对外发布用）
JAVA_HOME=.../jdk8u504-b01 gradle buildJAR            -> build/libs/TinyCat_V4.3.7.jar

# 带图版（内部测试，图打进 jar）
gradle buildJARWithImages                            -> build/libs/TinyCat_V4.3.7_images.jar

# 金币/首层 HP 校准用独立测试 Mod
gradle buildTrackerTestJAR                           -> build/libs/TinyCatTrackerTest_V0.2.0.jar
```

安装：把 jar 放进 `<游戏>/mods/`，**同名 modid 只能留一个**、重启游戏生效。
mods 里当前是 **4.3.7_images**（2026-10-24 部署，含火堆权重改版）；旧 4.2.2_images 已移到 `mods-backup/TinyCat_V4.2.2_images.jar.bak-before-4.3.3`。

## 2. 先读什么

| 想知道 | 读 |
|---|---|
| 每个数字怎么算、分母是什么、有哪些坑 | `docs/CORE-LOGIC.md`（口径唯一权威）|
| 谁在什么时候改过什么、有没有被偷偷改 | `docs/ALGORITHM-AUDIT.md`（审计证据 + 留痕表）|
| 数据怎么流动 | `docs/DATA-FLOW.md` |
| 金币/首层 HP 追踪、许可证评估 | `docs/STATE-NOTES-AND-GOLD.md` |
| 项目规则（构建、版本、禁止项） | `CLAUDE.md` |

## 3. 目录职责

```
src/main/java/tinycat/
  tinycat.java             Mod 入口：注册、初始化 NameMapper
  RunHistoryManager.java   扫 runs/ 目录 → Run 列表（存档位/日期/种子/放弃局过滤）
  NameMapper.java          本地汉化包 location/chinese_english_mapping.json（685 条，含怪物段）
  CardNameNormalizer.java  卡牌旧名→新名（Gash→Claw 之类），统计与导出共用
  stats/                   ★ 统计聚合：Run（一局）→ ClassStat（汇总）→ Mean/Rate
  ui/                      MoreStatsScreen（分页/筛选/下拉）、CharStatRenderer（渲染）
  patches/StatsScreenPatch 把原版统计页换成 TinyCat 的
  tier/                    ★ 导出层：TierListModel/Exporter、AllStatsExporter、MonsterTier*、ExportImagePack
tracker-test/              独立测试 Mod：写 runs_test/ 校验表 + runs/*.tinycat
scripts/                   校验与工具：verify_export.py / validate_gold_estimate.py / download_monster_images.py
location/                  uilocalization/strings*.json + chinese_english_mapping.json
docs/                      CORE-LOGIC / ALGORITHM-AUDIT / DATA-FLOW / STATE-NOTES / HANDOVER
```

## 4. 三条铁律

1. **改聚合只动 `stats/`**；改展示只动 `ui/` `patches/` `tier/`。两边不要混。
2. **口径改动必须先读 CORE-LOGIC、改完在 ALGORITHM-AUDIT 补一行**，并在回复里说清影响面。
3. **Java 8 语法**、不引第三方依赖、不改 `.tinycat` 键名、不改 `runs/` 里主 Mod 只读的约定。

## 5. 本轮（2026-10-23 ~ 10-24）做过的修复

| 问题 | 根因 | 改动文件 | 状态 |
|---|---|---|---|
| 梯度表中文名全是机翻 | 旧版有手写 `NAME_ZH` 表 | `tier/MonsterTierBuilder` 改调 `MonsterNames` | 已修 |
| 怪物名显示 `[MISSING_NAME]` | 游戏语言包查不到 key 时返回占位符而非 null，链路被短路 | `tier/MonsterNames.clean()` | 已修 |
| 怪物名没用本地汉化包 | 只查了游戏 `monsters.json`；本地汉化段是「中文→英文」方向，按英文 key 查不到 | `MonsterNames.localName()` 优先 `NameMapper.toChinese` | 已修 |
| 卡牌图 `NO IMAGE`（7 张） | **卡牌 ID ≠ 显示名**：Gash→Claw、Undo→Equilibrium、Redo→Recursion、Lockon→Bullseye、Steam→Steam Barrier、Steam Power→Overclock、Conserve Battery→Charge Battery；`CardLibrary.getCard(显示名)` 返回 null | `tier/TierImageExporter.resolveCard()`（按 `name/originalName/cardID` 建惰性索引） | **未修好**，见下一行 |
| 卡牌图 `NO IMAGE`（同上 7 张，4.2.3 真修） | 上一版按 `card.name` 建索引，但**游戏语言是 ZHS 时 `AbstractCard.name/originalName` 是中文**（"均衡"/"爪击"），索引里没有英文键；`normalizeKey` 还会把非 ASCII 字符剥成空串 | `tier/TierImageExporter`：新增 `putEnglishNames()` 读游戏自带 `localization/eng/cards.json` 建「英文卡名 → cardID」索引（语言无关）+ `LEGACY_CARD_IDS` 19 条兜底 + 查不到时打 warn 日志；同名不同卡（各色 Strike/Defend）标记为歧义不入索引；`TierListBuilder.isCurseOrStatus` 改用同一 resolver | 已修（4.2.3）|
| 导出图里没有怪物梯度 | `tierlist.jpg` 只画 TierListModel，怪物梯度是另一套渲染 | `tier/ExportImagePack` + 两个 Renderer 拆 `sectionHeight/drawSection` | 已修 |
| 样本下限把怪挡在附录外 | 原设计按门槛排除 | `MonsterTierEntry.lowSample` + 全部参与分档，右下角标「样本不足 n=X」 | 已改 |
| 导出「全角色汇总」行 = 当前角色 | `MoreStatsScreen.getClassStats` 用 switch 把 `classStats[4]` 换成了当前角色 | 始终 `getAllRuns(..., rotate=true)` | 已修 |
| 火堆/商店/尼奥没有梯度表 | 这三类的指标不是抓率（火堆页的 100% 是「次数/次数」），套固定阈值会全挤一档 | 新增 `tier/RankRule.java`（等人数四分位/S、可切换累计占比、相对最大值）、`tier/NeowShort.java`（尼奥概况短名）、`TierListBuilder.buildCampfire/buildShop/buildNeow`、`TierImageExporter.exportText`（无图源条目用像素文字占位图）、`Tier` 增加排名型档位 RS/RA/RB/RC/RD；统计页 4/14/15 出现导出按钮 | 已完成（4.3.0）|
| 排名型分档 S 档跨度过大 | 等人数硬切 25%，火堆第一幕 S 档 11 条从 24.8% 铺到 2.5%，而数据本身有断层（21.9%→8.6%） | 新增 `RankRule.HYBRID` 并设为 ACTIVE：S = ≥头名 50%，其余三等分 | 已完成（4.3.1）|
| 商店删牌没有梯度入口 | 删牌被 `isShopMeta` 排除，混进商品表会占 55%、把商品全压进 C 档 | 新增 `TierListBuilder.buildShopActions` → 「商店动作（四幕合计）」表，删牌/买卡/买遗物/买药水 单列 | 已完成（4.3.1）|
| 分档口径反复 | 4.3.1 用 HYBRID（头名断层），用户要求改回「直接按绝对次数排四分位」 | `RankRule.VALUE_QUARTILE` 设为 ACTIVE；Q1/Q2/Q3 用 inclusive 插值（等价 Excel QUARTILE.INC）。已知：商店表 Q1=1 → C 档空 | 已完成（4.3.2）|
| 分档口径定案 | 用户要求「档内差距不能过大、也不能出现空档」，且要用原始次数 | `RankRule.JENKS`（k=5，Fisher-Jenks DP，先按不同数值分组再加权，同值必同档）；实测 32 表跨度中位 1.50、空档 0；`VALUE_QUARTILE` 等 5 种旧规则保留可一行切换 | 已完成 |
| 图片主标写次数还是占比 | 次数体现「同名卡多张、升级多次」，占比体现跨角色/跨幕可比；只能有一个主标 | 火堆/商店/尼奥主标都写 `351 次`、副标 `24.81% · 351/1415`；尼奥分母 = 全部尼奥选择次数。CSV 为 分母/次数/占比/备注/图片 | 已完成 |
| 火堆行动表会被持有量稀释 | 神化只出现 32 次 / 8849 次锻造，行动次数少不等于不想升级，而是没有抓到 | 新增「火堆偏好」页与梯度表：行动层 = 行动次数 ÷ 火堆访问次数；卡牌层 = 锻造次数 ÷ 拥有张数；桥接层 = 占锻造、占火堆；一至四幕 + 合计；拥有 < 10 不参与；`ClassStat.campfireUpgradeStats` + `campfireVisits/Smiths` + `TierListScope.CAMPFIRE_UPGRADE` + `火堆偏好.xlsx` | 已完成 |
| 校验文件 `.tinycat` 看不到 | 只在 `runs/` 写，且日志只有路径 | 测试 Mod 0.2.0 写 `runs_test/`（含 `*.gold.txt` 金币校验表）+ 日志输出全部校验值 | 已改 |

## 6. 已知坑（都踩过）

1. 游戏语言包缺失条目返回 `"[MISSING_XXX]"` 而不是 null —— 任何"非空就用"的写法都会被短路。
2. `MonsterHelper.getEncounterName` 只覆盖组队遭遇（29 条官方名），单怪要靠 `monsters.json`。
3. 卡牌 ID 与显示名不一致（见上表 7 个），凡是"按名字查卡"的地方都要走 `TierImageExporter.resolveCard`（`TierListBuilder` 已改用它）。
   另外**游戏语言不是英语时，`AbstractCard.name` 是本地化名**，别拿它当英文键建索引；要英文名就查 `localization/eng/cards.json`。
4. 本地汉化包方向混用（英文→中文、中文→英文都有），用 `NameMapper.toChinese`，别自己按 key 查。
5. `actualHPLoss` 含战后回血（燃烧之血每场抵 6），战士低伤害怪会出现负战损 —— 口径特性不是 bug。
6. 每幕总战损按「每局一个样本」，单场均值按「每次遭遇一个样本」，两个分母别混。
7. `.run` 的 `neow_bonus_log` 是 camelCase `cardsRemoved`；`.run` 编码可能是 GBK。
8. A20 第三幕是双 Boss（50/51 层），`captureBossFloors` 第三幕取 `max`。
9. 抓率分幕用 `floor < 17`，与战损的动态 Boss 分幕是两套逻辑。
10. 火堆 / 商店 / 尼奥 / 火堆偏好走的是排名型档位（`RankRule.ACTIVE`，当前 `JENKS` k=5），档内按指标降序并标出实际占比；换规则只改 `RankRule.ACTIVE` 一行。
11. 尼奥选取率对带代价条目不可信：`.run` 的 skipped 代价全是 `NONE`，收不到跳过计数。现在页面与导出统一改为「一行一个 `(bonus,cost)`、按选中次数排名、占比 = 选中 ÷ 全部尼奥选择」，不再按 bonus 合并；要彻底恢复真实选取率得改 `.run` 记录侧，不能在统计层补。
12. 火堆每幕表必须含 PURGE/RECALL/REST/DIG/LIFT；只放卡牌、把行动踢到「四幕合计」是错的。升级目标卡附带「抓取 X/Y」上下文。
13. 火堆/商店/药水/尼奥没有独立图源，导出用 `images/text/*.png` 的像素文字占位图（5x7 字体只有 ASCII）。
14. 带图版 jar 只能内部测试（wiki 图 CC BY-NC-SA + Mega Crit 素材），不要上传创意工坊。
15. 火堆偏好只统计火堆 SMITH，不含磨刀石/战锤/课程/事件升级；已升级副本不会再次被敲，第二幕起升级率下降是口径特性。
    Pandora's Box / Astrolabe 的变牌去向、空鸟笼删牌未在 `.run` 记录，只能靠最终卡组补记，误差集中在 Strike/Defend。
16. 行动和锻造偏好不能互相替代：睡觉/抽烟/回忆是火堆行动占比，单卡升级率是拥有量口径。统一起点是「火堆访问次数」：
    行动占比 = 行动次数 ÷ 访问次数，占火堆 = 该卡升级次数 ÷ 访问次数（两者可相加），占锻造 = 升级次数 ÷ 锻造次数。
    不要用行动次数直接去和「拥有张数」相除。

## 7. 待办 / 未做

- [ ] SKIP 的「提供次数」（按卡槽位算）与 `ClassStat` 抓率口径不一致，是否统一待定。
- [ ] 中文名可能重名（旧口径下 蓝/红奴隶主都叫「奴隶贩子」，现走本地汉化包已区分为 蓝奴隶主/红奴隶主）。
- [ ] `图表/` 只覆盖有梯度的 7 个指标页；总体统计/事件/火堆行动/商店行动等表格页仍只有 xlsx。
- [ ] 金币估算常数（按 neow_bonus 查表）仍需用 `runs_test/*.gold.txt` 的实测值回标。
- [ ] `.tinycat` 曾出现"写完又消失"（外部清理所致），已有日志兜底，未定位清理者。

## 8. 自检与校验

```bash
# 导出包是否算对（原始 .run 独立复算 92 项）
python scripts/verify_export.py "<tierlist-export/...>" --class DEFECT

# 核心统计是否被改（空输出 = 没动）
git diff HEAD -- src/main/java/tinycat/stats/

# 装的 jar 与源码是否一致
md5sum "<游戏>/mods/TinyCat_V4.3.7_images.jar" build/libs/TinyCat_V4.3.7_images.jar

# 金币/首层 HP 估算误差
python scripts/validate_gold_estimate.py
```

## 9. 交付物

| 文件 | 说明 |
|---|---|
| `build/libs/TinyCat_V4.3.7.jar` | 普通版（不含图，可发布）|
| `build/libs/TinyCat_V4.3.7_images.jar` | 带图版（内部测试，64 张遭遇图）|
| `build/libs/TinyCatTrackerTest_V0.2.0.jar` | 校准用测试 Mod |
| `mods-backup/` | 历史版本备份（public / no-images / local-images / v4.1.3 / tracker 0.1.0）|

## 10. 给下一个 AI 的建议

1. 动任何数字前，先 `grep` 到实际代码位置，别靠文件名或注释推断；`stats/` 与 `tier/` 是两套代码。
2. 用户质疑"数字/图不对"时，**先跑 `verify_export.py`**，用原始 `.run` 说话，不要辩护。
3. 改完必须同步三处：`CORE-LOGIC`（口径）/ `ALGORITHM-AUDIT`（留痕）/ 必要时 `README`。
4. 回复时列出**具体改了哪些文件、影响哪些输出**，并说明要不要重启游戏。
