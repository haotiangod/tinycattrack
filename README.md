# TinyCat - 杀戮尖塔本地数据统计模组

纯本地离线运行的对局数据追踪与统计，不依赖任何外部服务器，也不需要额外安装状态记录类 Mod。

> 基于 [MaT1g3R/sts-stat-tracker](https://github.com/MaT1g3R/sts-stat-tracker)（MPL-2.0）修改。

---

## 基本信息

| 项目 | 内容 |
|------|------|
| ModID | `TinyCat` |
| 版本 | 4.1.0 |
| 作者 | tinycat |
| 依赖 | BaseMod, RunHistoryPlus |
| 许可证 | MPL-2.0 |

数据流与统计口径见 [docs/DATA-FLOW.md](docs/DATA-FLOW.md)，金币/血量追踪与 StateNotes 许可证评估见 [docs/STATE-NOTES-AND-GOLD.md](docs/STATE-NOTES-AND-GOLD.md)。

---

## 项目结构

```text
tinycat/
├── src/main/java/tinycat/
│   ├── tinycat.java              # 模组入口，生命周期与事件订阅
│   ├── RunStateTracker.java      # 局内状态追踪（第 1 层 HP、累计金币）
│   ├── Config.java               # SpireConfig 配置
│   ├── Localization.java         # 中/英文本地化
│   ├── LocalizationKeys.java     # 本地化键
│   ├── NameMapper.java           # 中英文名称映射
│   ├── CardNameNormalizer.java   # 卡牌名称归一化
│   ├── RunHistoryManager.java    # 历史 .run 文件加载
│   ├── Utils.java                # 反射与格式化工具
│   ├── YearMonth.java            # 年月工具
│   ├── patches/
│   │   └── StatsScreenPatch.java # 统计界面补丁与导出入口
│   ├── stats/
│   │   ├── ClassStat.java        # 统计聚合核心
│   │   ├── Run.java              # 单局模型（含第 0 层 HP、金币解析）
│   │   ├── RunData.java          # .run JSON 映射
│   │   ├── Card.java / CardChoice.java
│   │   ├── BossRelic.java / Neow.java / NeowBonusLog.java
│   │   ├── EncounterStats.java / ShopContent.java
│   │   ├── MetaScaling.java / PurgeSource.java
│   │   ├── Rate.java / Mean.java / SampleSize.java
│   │   ├── SeedFilter.java / WinStreak.java
│   ├── tier/                     # Tier List 构建、图片渲染与导出
│   └── ui/
│       ├── CharStatRenderer.java
│       └── MoreStatsScreen.java
├── src/main/resources/
│   ├── tinycat/img/
│   └── ModTheSpire.json
├── location/
│   ├── uilocalization/           # strings.json / strings-zh.json
│   └── chinese_english_mapping.json
├── docs/                         # 数据流与金币评估文档
├── scripts/
│   └── validate_gold_estimate.py # 金币估算 / 追踪数据校验
├── build.gradle.kts
└── build-local.bat
```

---

## 功能

### 对局采集
- 通过 BaseMod 事件记录对局，RunHistoryPlus 写出 `.run` 文件。
- 按存档位与角色分目录读取（`runs/IRONCLAD/`、`runs/1_DEFECT/` 等）。
- 只统计 A20；排除每日、特殊局、无尽局与 Mod 内容；种子局保留，由筛选选项控制。
- `.run` 读取严格尝试 UTF-8 → GBK，兼容非中文 locale。

### 统计面板
- 总体：胜率、死亡数、游戏时间、最高分、最高连胜。
- 卡牌：第一幕 / 第一幕后选牌率与胜率。
- Neow 奖励、Boss 遗物（含交换）、战斗遭遇、事件、遗物、商店遗物。
- 删牌来源（商店 / 事件 / Neow / 篝火 / 空鸟笼）、问号节点、火堆行动、商店行动。
- 伤害：平均承受伤害、实际 HP 损失（含/不含治疗）。

### 筛选
- 日期范围、角色（四角色及汇总）、统计类型、是否包含放弃局。
- 种子局：全部 / 不含种子 / 仅种子。
- 最小样本量过滤。

### 导出
- 在总体统计页右上角点击“导出”，生成四组 Tier List、`summary.csv`、`all_stats.csv`、HTML、`tierlist.jpg/png` 及卡图/遗物图。
- 图片取自本地游戏资源并缓存，重复导出不重复提取。
- 导出结果只显示“导出完成/失败”，完整路径写入日志。

---

## 第 0 层 HP 与金币追踪

TinyCat 自己记录状态，写入 `runs/{runDir}/{start_ts}.tinycat`（key=value）：

```text
version=1
start_ts=...
floor0_hp=...        # 仅在开局第 1 层、游戏时间 < 300 秒时记录
start_gold=...
gold_gained=...      # CardCrawlGame.goldGained，精确累计获得金币
gold=...             # 当前剩余金币
hp=...
floor=...
updated_ts=...
```

- 由 `PostDungeonUpdateSubscriber` 每帧检查，仅在楼层或累计金币变化时覆写，开销很小。
- 楼层刷新（读档）后按 `now - playtime` 寻找 1 小时内最近的 `.tinycat` 重新挂接。
- `Run` 加载历史时，用“与 `.run` 结束时间最接近的 `updated_ts`（6 小时内，且 `start_ts` 不晚于结束时间）”匹配状态文件。
- 有状态文件时，第 1 层 HP 与累计金币为精确值，不再标记估算。
- 没有状态文件的历史局回退到估算：
  - 第 1 层 HP：Ironclad `floor1HP + floor1Damage - 6`，其他角色 `+0`。
  - 累计金币：`gold_per_floor` 正增量 + Neow 扣除修正 + MawBank。
  - UI/CSV 会标注估算。

### 金币估算准确度

在 1,113 局真实数据中，有 305 局可从分数明细（“下钱雨啦”等）提取精确累计金币，用于校验估算法：

| 指标 | 数值 |
|---|---|
| 平均绝对误差 | 38.0 |
| 中位绝对误差 | 39 |
| P90 绝对误差 | 64 |
| 最大绝对误差 | 160 |
| 带符号平均误差 | -2.5 |

新版本对装入 `.tinycat` 的对局直接使用精确值；估算仅作为旧数据回退。

---

## 战损统计的幕划分

幕边界不是固定楼层，而是从 `damage_taken` 中识别各幕 Boss 的实际楼层：

| 幕 | 范围 | 说明 |
|---|---|---|
| 第一幕 | 第 1 层 ~ 第一幕 Boss 所在层 | Boss 通常 16，异变路线可能 17 |
| 第二幕 | Boss 下一层 ~ 第二幕 Boss 所在层 | 通常 33，可能 34 |
| 第三幕 | Boss 下一层 ~ 最后一个第三幕 Boss 所在层 | 通常 50/51，可能 52/53 |
| 第四幕 | 第三幕 Boss 之后 | 篝火 53/54、商店 54/55、矛盾 55–58、心脏 56–59 |

- 识别依据：Boss 名称（Hexaghost / Slime Boss / The Guardian、Automaton / Collector / Champ、Time Eater / Awakened One / Donu and Deca）在 `damage_taken` 中的楼层。
- 缺少 Boss 数据时回退到旧的 `<=17 / <=34 / <=52` 边界。
- 传送门（Portal）路线单独处理。
- 在 1,113 局真实数据上，新算法对 Boss 归属 0 误判；仅 5 条原本被归入第四幕的第三幕 Boss（52/53 层）被纠正。

---

## 构建与安装

### 前置条件
- JDK 8
- Gradle 8.0+
- 杀戮尖塔游戏本体
- ModTheSpire + BaseMod + RunHistoryPlus

### 构建
```bash
# Windows 快捷方式
build-local.bat

# 或
gradle buildJAR
```

产物：`build/libs/TinyCat_V4.1.0.jar`。

### 安装
将 JAR 放入游戏的 `mods/` 目录。升级前删除旧版本 JAR，避免同时加载两个 TinyCat。

---

## 校验脚本

```bash
python scripts/validate_gold_estimate.py
```

读取真实 `.run` 与 `.tinycat`，输出：
- 估算金币与分数明细精确值的误差分布；
- 状态文件匹配情况；
- 各幕 Boss 楼层分布与实际幕范围；
- 新旧幕边界算法的差异。

---

## 已知限制

1. 未生成 `.tinycat` 的旧对局仍使用估算金币与估算第 1 层 HP，UI/CSV 会标注。
2. 状态文件依赖时间匹配：若在结算界面停留超过 6 小时，可能匹配失败。
3. 只有第 1 层、游戏时间 300 秒内启动的追踪才会记录 `floor0_hp`；中途读入旧档无法还原开局 HP。
4. Mod 内容仅通过名称是否含 `:` 判断，可能漏判。
5. `Run.java` 中的卡牌/遗物/敌人黑名单需要随游戏与 Mod 更新手动维护。
6. 第 1 层 HP 回退算法中 Ironclad 的 Burning Blood 回血 6 为硬编码。
7. 核心算法目前只有校验脚本，没有 JUnit 单元测试。

---

## 更新日志

### v4.1.0
- 新增 `RunStateTracker`：本地记录第 1 层 HP、累计获得金币、当前金币与 HP，不依赖外部 Mod。
- 金币改用 `CardCrawlGame.goldGained` 精确值；移除 gold-tracker 依赖与相关读取代码。
- 第 0 层 HP 改为状态文件精确读取，移除静态字段与旧的伴生文件机制。
- 战损统计按实际 Boss 楼层划分幕，修正 52/53 层第三幕 Boss 被归入第四幕的问题。
- 导出按钮移至右上角，不再遮挡内容；导出成功/失败不再显示目录路径。
- 修复死亡率页未传入幕号导致问号节点统计不显示的问题。
- 清理废弃补丁/类、冗余脚本与 AI 工具说明文件。

### v4.0.9
- Tier List 导出（CSV / JSON / HTML / 图片）与本地图片缓存。
- 问号节点、火堆行动、商店行动、伤害统计。
- 修复第 0 层 HP 静态字段污染、中文客户端角色配色、删牌统计分类。
- `.run` 读取增加 GBK 回退。

### v4.0.0
- 从 StatsTracker 重构为 TinyCat，移除全部网络代码，加入中文本地化。
