# TinyCat - 杀戮尖塔本地数据统计模组

纯本地离线运行的对局数据追踪与统计，不依赖任何外部服务器，也不需要额外安装状态记录类 Mod。

> 基于 [MaT1g3R/sts-stat-tracker](https://github.com/MaT1g3R/sts-stat-tracker)（MPL-2.0）修改。

---

## 基本信息

| 项目 | 内容 |
|------|------|
| ModID | `TinyCat` |
| 版本 | 4.5.6 |
| 作者 | tinycat |
| 依赖 | BaseMod, RunHistoryPlus |
| 许可证 | MPL-2.0 |

**想搞清楚每个数字是怎么算出来的 → [docs/CORE-LOGIC.md](docs/CORE-LOGIC.md)（全指标公式、分母口径、代码位置、已知坑、改代码协议）**。
数据流见 [docs/DATA-FLOW.md](docs/DATA-FLOW.md)，口径变更留痕见 [docs/ALGORITHM-AUDIT.md](docs/ALGORITHM-AUDIT.md)，金币/血量追踪与 StateNotes 许可证评估见 [docs/STATE-NOTES-AND-GOLD.md](docs/STATE-NOTES-AND-GOLD.md)。

---

## 项目结构

```text
tinycat/
├── src/main/java/tinycat/
│   ├── tinycat.java              # 模组入口，生命周期与事件订阅
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
├── tracker-test/                 # 独立测试 Mod，不在主 JAR 内
│   ├── src/main/java/tinycat/tracker/
│   │   ├── TinyCatTrackerTest.java
│   │   └── RunStateTracker.java  # 写 .tinycat，仅用于校准
│   └── src/main/resources/ModTheSpire.json
├── location/
│   ├── uilocalization/           # strings.json / strings-zh.json
│   └── chinese_english_mapping.json
├── docs/                         # CORE-LOGIC（口径权威）/ ALGORITHM-AUDIT（变更留痕）/ DATA-FLOW / STATE-NOTES
├── scripts/
│   ├── validate_gold_estimate.py # 金币估算 / 追踪数据校验
│   └── download_monster_images.py # 从灰机 wiki 生成本地怪物图包（不提交图片）
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
- 删牌来源（商店 / 事件 / Neow / 篝火 / 空鸟笼）、问号节点、火堆行动、商店行动、火堆偏好。
- 伤害：平均承受伤害、实际 HP 损失（含/不含治疗）。

### 筛选
- 日期范围、角色（四角色及汇总）、统计类型、是否包含放弃局。
- 种子局：全部 / 不含种子 / 仅种子。
- 最小样本量过滤（统计页表格；怪物危险度梯度使用独立的分层门槛）。

### 导出
- 在统计页右上角点击“导出”，生成 Tier List、中文 Excel 统计工作簿、HTML、`tierlist.jpg/png` 及卡图/遗物图。
- “全部导出”按游戏内统计分页生成 13 个 `.xlsx`：总体、两张卡牌选取率、卡牌胜率、尼奥、Boss 遗物、遭遇战伤害（三张表）、死亡率与药水、遗物、事件、火堆、商店、火堆偏好。
- 同类页面合并：Boss 遗物选取/胜率、死亡率/药水、遗物胜率/购买胜率各为一个工作簿；卡牌选取率保持第一幕/第一幕后两个独立文件。
- 表头、表名、取值和说明默认中文，卡牌/遗物/事件保留英文名辅助列。
- **“全部导出”会额外并入战损梯度表**：`怪物危险度.xlsx` + `images/monsters/`，并生成 `图表/` 目录：
  - `00-完整梯度图.png/.jpg`：卡牌抓率 + BOSS 遗物 + 怪物危险度，**一张图发完**；
  - `01-…` 起：按前端指标页拆开的单页图（第一幕卡牌 / 第一幕后卡牌 / BOSS 遗物 / 怪物危险度三幕）。
- 单页导出（只导卡牌或只导 BOSS 遗物）不会带怪物危险度。
- 图片取自本地游戏资源并缓存，重复导出不重复提取。
- 导出结果只显示“导出完成/失败”，完整路径写入日志。

### 怪物危险度梯度
- 按幕统计 Boss 与非 Boss（含精英）的平均实际战损，Boss 单独 T0，非 Boss 等人数分 T1-T4。
- **不做样本下限过滤**：所有遭遇都参与 T0-T4 分档。Boss ≥ 5、单角色非 Boss ≥ 10、全角色汇总非 Boss ≥ 30 只作为"样本参考值"，
  低于参考值的条目在图片/HTML 条目右下角标注「样本不足 n=X」、xlsx 备注列写「样本不足」，提醒结论不稳定，但仍照常排名。
- 名称优先读 TinyCat 自带汉化包（`location/chinese_english_mapping.json` 的「怪物名称映射」段，社区叫法如 六火亡魂 / 乐嘉 / 三柱 / 蟹粥），
  未命中再回退游戏内 `monsters.json` / `RunHistoryMonsterNames` 官方译名。
- 每个条目附带样本占比、净战损合计和战损贡献占比，用于判断样本集中度与总战损来源。

---

## 第 0 层 HP 与金币追踪（可选测试版）

主 TinyCat **不写任何状态文件**，只在对局目录发现 `.tinycat` 时读取；没有就回退估算。这样普通安装不会在后台产生额外文件，也不会与其它 Mod 争抢写入。

精确值由独立的测试 Mod `TinyCatTrackerTest` 生成，写入 `runs/{runDir}/{start_ts}.tinycat`（key=value）：

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

- 测试 Mod 由 `PostDungeonUpdateSubscriber` 每帧检查，仅在楼层或累计金币变化时覆写。
- 楼层刷新（读档）后按 `now - playtime` 寻找 1 小时内最近的 `.tinycat` 重新挂接。
- `Run` 加载历史时，用“与 `.run` 结束时间最接近的 `updated_ts`（6 小时内，且 `start_ts` 不晚于结束时间）”匹配状态文件。
- 有状态文件时，第 1 层 HP 与累计金币为精确值，不再标记估算。
- 没有状态文件的历史局回退到估算：
  - 第 1 层 HP：Ironclad `floor1HP + floor1Damage - 6`，其他角色 `+0`。
  - 累计金币：`gold_per_floor` 正增量 + Neow 扣除修正 + MawBank。
  - 金币在 UI/CSV 标注估算；第 1 层 HP 仅在 CSV 保留估算计数，界面不再显示提示。
- 测试 Mod 与主 Mod 的 modid 不同，可以同时安装；不装测试 Mod 时，主 Mod 的行为与普通统计完全一致。

### 测试 Mod 的构建与使用

```bash
gradle buildTrackerTestJAR
```

产物 `build/libs/TinyCatTrackerTest_V0.1.0.jar`。把它和 `TinyCat_V4.5.4.jar` 一起放入 `mods/`，打几局后运行校验脚本，即可对照状态文件与分数明细。测试 Mod 只写状态，不参与统计界面渲染。

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

## 战损统计的幕划分与楼层范围

幕边界不是固定楼层，而是从 `damage_taken` 中识别各幕 Boss 的实际楼层。统计口径是“捏奥之后 → 本幕 Boss”，并跳过奖励层：

| 幕 | 统计楼层范围 | 跳过 |
|---|---|---|
| 第一幕 | 第 1 层 ~ 第一幕 Boss 所在层 | 宝箱层 `T` |
| 第二幕 | 第一幕 Boss + 1 ~ 第二幕 Boss 所在层 | 第一幕 Boss + 1（奖励/过渡层）、宝箱层 `T` |
| 第三幕 | 第二幕 Boss + 1 ~ 第三幕 Boss 所在层 | 第二幕 Boss + 1、宝箱层 `T` |
| 第四幕 | 第三幕 Boss + 1 ~ 结算 | 第三幕 Boss + 1、宝箱层 `T` |

- 识别依据：Boss 名称（Hexaghost / Slime Boss / The Guardian、Automaton / Collector / Champ、Time Eater / Awakened One / Donu and Deca）在 `damage_taken` 中的楼层。
- 宝箱层由 `path_per_floor` 的 `T` 判定；Boss 后一层固定视为奖励/过渡层。
- 真实数据审计（1,113 局）：奖励层上的战斗 0 场、事件 0 次，因此该修正不改变现有数字；它的作用是防止 Boss 后的回血/宝箱效果被算进下一幕。
- “每幕总战损”目前是逐条战斗/事件净 HP 变化之和；篝火休息等回血没有对应战斗条目，不会被抵减。它表示本幕累计受到的伤害，不等于“本幕开场 HP − Boss 后 HP”。
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

# 可选：构建金币/首层 HP 校准用的独立测试 Mod
gradle buildTrackerTestJAR

# 可选（内部测试用）：把本地怪物图包打进 jar，单文件即可带图
gradle buildJARWithImages
```

产物：
- 主 Mod（不含图）：`build/libs/TinyCat_V4.5.5.jar`
- 主 Mod（**带图**，内部测试）：`build/libs/TinyCat_V4.5.5_images.jar`（约 6.8 MB）
- 测试 Mod：`build/libs/TinyCatTrackerTest_V0.2.0.jar`

带图版说明：
- 图来自 `mods/TinyCat/monster-images/*.png`（64 张遭遇别名图），构建时打进 `tinycat/monsters/`；
  运行时 `MonsterImageLibrary` 仍**优先读**外部目录，所以想验证 jar 内图，把那个目录临时改名即可。
- 图是灰机 wiki 的 CC BY-NC-SA 内容 + Mega Crit 素材，**只能内部测试**，不要上传创意工坊或随包分发。
- 带图版与普通版 modid 相同，`mods/` 里只能留一个。

### 安装
将 JAR 放入游戏的 `mods/` 目录。升级前删除旧版本 JAR，避免同时加载两个 TinyCat。

### 怪物图片（本地图源）

梯度表的图片从 `<game>/mods/TinyCat/monster-images/` 读取，模组本身不联网、不打包图包。可用脚本从灰机 wiki 的[敌人图鉴](https://sts.huijiwiki.com/wiki/%E6%95%8C%E4%BA%BA%E5%9B%BE%E9%89%B4)批量生成本地图包：

```bash
python scripts/download_monster_images.py --replace
```

- 下载 46 张图鉴原图，并生成 64 个以英文遭遇名命名的别名；原图保留在 `wiki/`。
- 别名默认裁掉顶部名称、底部稀有度徽章并做圆角；`--no-crop` 保留完整图鉴卡。
- 默认输出到 Steam 游戏目录；其它安装位置用 `--out` 指定，`--replace` 会先把旧图包改名备份。
- 图源为 CC BY-NC-SA 4.0，游戏素材版权归 Mega Crit；仅限本地非商业使用。
- 图包被 `.gitignore` 忽略，不要提交到仓库或打进 JAR / Release。
- 读取时优先匹配英文遭遇名；旧图包文件名映射只作为兼容回退。

---

## 校验脚本

```bash
python scripts/validate_gold_estimate.py
```

读取真实 `.run` 与 `.tinycat`，输出：
- 估算金币与分数明细精确值的误差分布；
- 状态文件匹配情况；
- 各幕 Boss 楼层分布与实际幕范围；
- 新旧幕边界算法的差异；
- 奖励/过渡层上仍发生战斗或事件的数量。

### 导出包校验（verify_export.py）

不信任导出结果时，用原始 `.run` 独立复算再和导出包逐项比对：

```bash
python scripts/verify_export.py "<tierlist-export/TinyCat_TierList_XXX_时间>" --class DEFECT
```

会读 `总体统计.xlsx / 遭遇战伤害.xlsx / 怪物危险度.xlsx / data.csv`，复算并逐项打 OK / DIFF：
对局数、胜场、总层数、击杀、分数、金币估算、删牌五来源、问号与事件数、卡牌抓率、
每个遭遇的平均战损（含每幕战斗总计）、梯度表档位/样本/均值，以及「全角色汇总」行。

> 复刻的口径全部来自 `docs/CORE-LOGIC.md`；若导出后出现 DIFF，先确认它是口径差异还是真 bug。

---

## 已知限制

1. 主 Mod 不生成 `.tinycat`；未安装 `TinyCatTrackerTest` 的对局仍使用估算金币与估算第 1 层 HP。金币在 UI/CSV 标注估算，第 1 层 HP 仅在 CSV 保留估算计数。
2. 安装测试 Mod 后，状态文件依赖时间匹配：若在结算界面停留超过 6 小时，可能匹配失败。
3. 测试 Mod 只在第 1 层、游戏时间 300 秒内记录 `floor0_hp`；中途读入旧档无法还原开局 HP。
4. Mod 内容仅通过名称是否含 `:` 判断，可能漏判。
5. `Run.java` 中的卡牌/遗物/敌人黑名单需要随游戏与 Mod 更新手动维护。
6. 第 1 层 HP 回退算法中 Ironclad 的 Burning Blood 回血 6 为硬编码。
7. 核心算法目前只有校验脚本，没有 JUnit 单元测试。
8. “每幕总战损”按战斗/事件条目累加，不含篝火等回血，因此不等于净 HP 差。

---

## 更新日志

### 未发布（本地工作区，版本号待定）

> 以下改动都在工作区里，尚未提交、未定版本号。发布时由项目所有者决定版本号与提交信息。

- **排名型梯度（火堆 / 商店 / 尼奥 / 火堆偏好）**：新增 `tier/RankRule`（分档规则）、`tier/NeowShort`（尼奥概况短名）、
  `TierListBuilder.buildCampfire/buildShop/buildNeow`、`TierImageExporter.exportText`（无图源条目用像素文字占位图），
  `Tier` 增加排名档位 RS/RA/RB/RC/RD。统计页 4/14/15/16 出现导出按钮。
- **火堆**：每幕一张「火堆行动」，同时列出升级目标卡与睡觉 / 回忆 / 删牌 / 挖掘 / 举重；
  指标 = 次数 ÷ 该幕火堆行动总数。升级目标卡附「抓取 X/Y」上下文（神话次数少通常是没有抓到，不是不升级）。
- **火堆偏好**：新增统计页与梯度表，把「行动」和「锻造偏好」放在同一批火堆访问上分三层看：
  - 行动层 = 锻造 / 睡觉 / 回忆 / 抽烟 / 举重 / 挖掘次数 ÷ 火堆访问次数（合计 100%）；
  - 卡牌层 = 该幕锻造次数 ÷ 到该幕结束累计拥有张数（初始牌组 + 抓取 + 商店 + 事件 + 变牌，扣掉删牌/变牌移出）；
    四幕合计的分母用整局获得过的副本总数，避免删牌把分母压小；
  - 桥接层 = 升级次数 ÷ 锻造次数（占锻造）、升级次数 ÷ 火堆访问次数（占火堆），后者与行动占比同分母，可直接比较。
  页面与梯度按第一至第四幕排列，四幕合计放最后；只统计火堆锻造，已升级的副本不会再次被敲，
  所以同一张牌第二幕起升级率自然下降；拥有不足 10 张不参与排名。
- **商店**：每幕「购买商品」（卡 / 遗物 / 药水混排，剔除删牌与汇总行）+ 一张「商店动作（四幕合计）」单列删牌。
- **尼奥**：一行 = 一个「奖励 + 代价」选项（绝不按奖励合并），按选中次数排名；
  占比 = 选中次数 ÷ 尼奥选择总次数。简称如「掉血删2」「血上限删2」「诅咒250」「掉钱250」，
  字符串集中维护在 `tier/NeowShort.java`。
- **卡图修复**：改用游戏自带 `localization/eng/cards.json` 建「英文卡名 → cardID」索引，
  修掉中文客户端下 7 张卡永远 `NO IMAGE` 的问题；查不到时打 warn 而不是静默兜底。
- **分档规则改为 `RankRule.JENKS`（Jenks 自然断点，k=5）**：用 Fisher-Jenks 动态规划切成 5 段，
  使各档内部平方和之和最小。先按不同数值分组再做 DP，所以**同值必定同档**、每档至少 1 条。
  实测 32 张表：档内跨度中位 **1.50**（等人数 2.29）、空档 **0** 个（数值四分位 5 个）、
  80% 重采样档位稳定率 火堆 86.4% / 商店 80.9%。
  档位为 S/A/B/C/D 五档；指标只有 3~4 个不同取值时底部档留空（例如「行动类型」表）。
- **导出主标改为「原始次数」**：分档是在次数上切的，所以图片大数字写 `351 次`，
  副标写 `24.81% · 351/1415`（占比 + 分子/分母）。尼奥的分母统一是「尼奥选择总次数」，
  不是逐条不同的提供次数——`.run` 里被跳过选项的代价全是 `NONE`，算不出带代价选项的真实选取率。
  同一张表内「次数」与「占比」是等比缩放，排序与档位完全一致（32 张表 100% 同序）。
- 其余规则（`QUARTILE` / `CUMULATIVE` / `RELATIVE` / `HYBRID` / `VALUE_QUARTILE`）全部保留，可一行切换。

### v4.5.6

**标准化率改用 Plackett-Luce（整屏选择）+ 弱先验**
- 旧的 Bradley-Terry 把一次「3 选 1」拆成 3 场两两对决，等于把同一个决定当成 3 份独立证据；
  再叠加 `a=b=1` 的强先验，把「几乎全胜」的牌和「出场极多但常输」的牌压到同一个区间。
  实测机器人第一幕出现 **快检 7.09% 排第 4、冷头 7.46% 高于快检** ——
  快检 92/94、冷头 241/301，且快检与冷头直接同屏时从未输过，这个排名违背数据本身
- 改为 Plackett-Luce：一次奖励屏就是一个选择集，`P(选中 i | S) = s_i / Σ_{j∈S} s_j`，
  整屏一起进似然；先验改为 `a=1, b=0.01`（每个条目 1 次假想胜、0.01 次假想出场）
- 先验用 5 折（按局分组）留出对数损失选：卡牌 `b=1 → b=0.01` 为 **0.6179 → 0.5845**
  （top-1 0.746→0.752）；尼奥 **0.6052 → 0.5129**（top-1 0.771→0.798）
- 机器人第一幕实测（406 局 / 3355 屏）：快检 **23.54%（第 1）**、偏差认知 16.87%、
  电动力学 15.71%、碎片整理 12.29%、冷头 **2.51%（第 9）** —— 直接交手记录终于被尊重
- **尼奥表同步换成同一模型**：`NeowRanker` 也改成整屏 PL + `b=0.01`；
  同时修掉「出现/被选」被两两矩阵放大 3 倍的问题（一次选择记成 3 条对决 → 分母 3p+s），
  现在直接取 `neowPickRate` 的逐局计数
- **标准化视图修复**：分档按排名档（RS..RZ）切，不再出现「每一档都是 0 条」的空图；
  视图改为在图片导出之后构建，副本能带上 `imagePath`，不再整张图没有卡图
- 区间仍是整屏重采样 1000 次（固定种子）；点估计按 1e-9 收敛，重采样按 1e-5 收敛
  （端点差 <0.003pp，单表耗时约 11.4s → 3.0s）
- `verify_export.py` 的独立复算同步改为整屏 PL + `b=0.01`

### v4.5.5

**标准化率 95%CI 改为整屏重采样**
- 旧的按配对格子 bootstrap(100) 把一次「3 张牌 + 跳过」当成 3 份独立证据，
  实际它们来自同一个决定，区间约窄 1.5 倍；且 2.5% 分位只取到第 3 个样本，端点会跳
- 改为按「一次奖励屏 / 一次尼奥选择」整组重采样 1000 次，同屏的 3 条对决一起抽
- **点估计与份额完全不变**（72 张卡最大差 5e-7pp），只影响 95%CI 的上下界
- 实测卡牌表 CI 宽度中位 0.19 → 0.30pp；尼奥表由 100 次升到 1000 次后端点不再随抽样跳动
- 没有选择记录可用的旧数据仍走格子 bootstrap(100) 兜底

### v4.5.4

**卡牌抓取率新增标准化率（导出专用）**
- 卡牌奖励同样拆成两两对决（选中卡胜每个未选项），用 Bradley-Terry 估计强度
- SKIP 始终作为对手参与比较：每次奖励里未选中的选项都会补一个 SKIP（持有颂钵后改补颂钵），
  和 `Run.java` 的语义一致；颂钵在矩阵里并入 SKIP，不单独成项
- Act 1（floor < 17）与 Act 1 之后分别拟合，因为两段的候选池和跳过率差异很大
- 标准化率 = 强度份额（合计 100%），与简单率量纲不同，两条数不能直接相减
- **不做复数抓位补偿**：同一张卡在一局里第 2 次被提供时选中率低约 12pp，
  但按抓位分层重新标准化后名次只动 0.5–1.0 位，因此只在导出说明里写明，不改算法
- **0 抓取卡不要看名次**：被提供 200+ 次却 0 抓的卡强度会一起塌到份额 0.00–0.01%，
  它们之间的先后顺序是数值噪声，不是强弱

**导出图片双口径**
- 第一套图仍是简单率（`tierlist.png/.jpg/.html`、`图表/00-完整梯度图`），和游戏内页面一致
- 第二套图是标准化率（`tierlist_standardized.png/.jpg/.html`、`图表/*-标准化率`），
  两张图标题都标了口径，避免混用
- CSV / JSON / xlsx 的卡牌表都多出标准化率与 95%CI 列；Mod 前端界面一律只显示简单率

### v4.5.3

**火堆偏好改成「统一榜」**
- 「升级」不再是一个总选项，拆回每张可敲的牌，与抽烟 / 举重 / 挖掘 / 回忆放在同一张榜上按占比排序
- 分母 = 该次火堆的「池子」= 可敲卡种 + 可用遗物行动 + 可用回忆；随机基线 = 100 ÷ 平均池子
- 咖啡杯禁用睡觉、锤子禁用升级：被禁用的选项不再计入分母（原来 `REST` 恒可选，把分母撑大了）
- 睡觉单列（约束是血量，不进池子），以组备注挂在导出表标题下
- 星盘 / 空鸟笼删牌无逐张日志，用终局卡组做双向补差

**尼奥选项新增标准化率**
- 按 `(奖励, 代价)` 逐项统计，不同代价算不同选项，不合并
- 分母改为「该选项出现次数」（选中 + 被跳过），不再是总局数
- 新增 Bradley-Terry 标准化率：把每局「1 选中 + 3 跳过」拆成 3 场两两对决估计强度，剔除对手强弱对简单率的扭曲
- 简单率给 Wilson 95%CI，标准化率给整屏 bootstrap(1000) 95%CI（一次「1 选中 + 3 跳过」的 3 条对决整组重采样）；两条区间重叠时名次差不算真实差异
- `尼奥奖励.xlsx` 拆成「简单率 / 标准化率」两张表；CSV 备注列、JSON（`standardized_pct` 等）也带上了

**商店**
- 药水不再被 `RelicLibrary` 的 Circlet 兜底误判成遗物（之前所有药水都显示成「头环」）
- 药水名两种写法（`PowerPotion` / `Swift Potion`）归一化为 ID
- 样本量门槛按「买过几次」，不再用统一分母（选 4 时买过 1 次的条目会真的消失）
- 新增药水图片导出 `images/potions/`

**其它**
- `MOD_VERSION` 改为运行时读 `ModTheSpire.json`，导出页不再永远显示 4.2.2
- 从 `desktop-1.0.jar` 的 `localization/zhs` 补了 12 个卡名官方汉化
- 分档新增 <1% 独立档 `R0`，并修复该档在梯度图/HTML 中被跳过的问题
- 火堆行动 / 商店行动页改回显示次数（不是占比）

### v4.2.2
- 新增 `scripts/download_monster_images.py`：从灰机 wiki 敌人图鉴下载 46 张原图并生成 64 个遭遇别名；默认裁掉标题、稀有度徽章并做圆角（`--no-crop` 可保留整图）。图源仅限本地非商业使用（CC BY-NC-SA 4.0），不随仓库或 JAR 分发。
- 怪物图片优先按英文遭遇名解析，旧图包文件名映射保留为兼容回退；无图条目仍使用占位图。

### v4.2.1
- 怪物危险度梯度改用分层样本门槛：Boss 5、单角色非 Boss 10、全角色汇总非 Boss 30，不再使用固定 30 且不再受统计页最小样本下拉框影响。
- 低样本条目不再静默排除，改为在每幕末尾的“样本不足”区保留展示；Excel、CSV、JSON、HTML、PNG/JPG 均同步。
- 新增样本占比、净战损合计、战损贡献占比三列；JSON 升级到 schema v2，并明确 `tier: null` 表示未参与分档。

### v4.2.0
- 新增“怪物危险度梯度”导出（统计页“遭遇战平均战损”右上方按钮）：按每幕平均实际战损排名，Boss 单独 T0，非 Boss（含精英）等人数分 T1-T4，T1 最高危。
- 导出包包含中文 Excel、CSV、JSON、HTML、整图 PNG/JPG 与本地图片；不使用固定战损阈值，事件战与第四幕固定战不参与。
- 怪物图片从游戏目录 `mods/TinyCat/monster-images/` 本地读取；图包不随模组分发，缺失图用本地占位图。

### v4.1.3
- 导出默认改为中文：`data.csv` 表头/取值与所有统计工作簿均为中文，英文名称保留为辅助列。
- “全部导出”按游戏内统计分页生成 12 个 Excel 工作簿；同类页面合并，例如 Boss 遗物选取/胜率合并为一个工作簿。
- 卡牌选取率保持“第一幕”与“第一幕后”两个独立 Excel；战损/受到伤害/有无回复合并为一个工作簿的三张表。
- 新增零依赖 `XlsxWriter`，直接生成 OOXML `.xlsx`，不引入 Apache POI。
- 单页导出只生成该页相关的工作簿；`tierlist.html` 与 `README.txt` 同步列出 Excel 文件。

### v4.1.2
- 移除统计界面的“第1层战损：估算”提示；历史局几乎全部为估算，该提示没有统计意义。CSV 中仍保留估算局计数。

### v4.1.1
- 主 Mod 移除 `RunStateTracker` 与帧更新写入；金币/首层 HP 状态改由独立测试 Mod `TinyCatTrackerTest`（`tracker-test/`）生成，主 Mod 仅在发现 `.tinycat` 时只读。
- 战损统计跳过宝箱层（`path_per_floor = "T"`）与 Boss 后奖励/过渡层；每幕范围按“捏奥 → 本幕 Boss”界定。
- 校验脚本新增奖励/过渡层战斗与事件审计，文档同步测试 Mod 的构建、安装与校准流程。

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
