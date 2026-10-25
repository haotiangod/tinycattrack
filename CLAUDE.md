# TinyCat — 项目规则

> **AI 接手第一步**：读 `docs/HANDOVER.md`（现状/产物/本轮修了什么/待办）。
> 查口径读 `docs/CORE-LOGIC.md`（所有指标的公式、分母口径、代码位置、已知坑、修改协议）。
> 口径变更记录在 `docs/ALGORITHM-AUDIT.md`；数据流见 `docs/DATA-FLOW.md`。

《杀戮尖塔》(Slay the Spire) 的本地数据统计 Mod。Java 8 + Gradle 8.0（Kotlin DSL）+ ModTheSpire 生态。

## 构建环境

| 组件 | 路径 |
|---|---|
| JDK 8 (Temurin 8u504-b01) | `C:\Users\MI\.workbuddy\binaries\jdk\jdk8u504-b01` |
| Gradle 8.0 | `C:\Users\MI\.workbuddy\binaries\gradle\gradle-8.0` |
| Python 3.12.10 | `C:\Users\MI\.workbuddy\binaries\python\versions\3.12.10\python.exe` |
| 游戏本体 | `C:\Program Files (x86)\Steam\steamapps\common\SlayTheSpire` |
| 依赖 JAR | `C:\Program Files (x86)\Steam\steamapps\workshop\content\646570\<id>\` |

- **必须用 JDK 8 编译**，目标 Java 8 字节码。
- Gradle 8 需要 Java 8 以上运行；用 `gradlew buildJAR` 或 `build-local.bat`。
- 产物：`build/libs/TinyCat_V4.3.7.jar`（不含图）；内部测试用带图版 `build/libs/TinyCat_V4.3.7_images.jar`（`gradle buildJARWithImages`，把 `mods/TinyCat/monster-images/*.png` 打进 `tinycat/monsters/`，**不得分发**）；测试 Mod 为 `build/libs/TinyCatTrackerTest_V0.2.0.jar`。
- 版本号同时改 `build.gradle.kts` 的 `versionTag`、`src/main/resources/ModTheSpire.json` 的 `version`；测试 Mod 版本在 `tracker-test/src/main/resources/ModTheSpire.json`。

## 当前状态

- 版本 **4.3.3**（火堆偏好改为 W 主口径 + A 替补，选项层补全）：主 Mod 不再写 `.tinycat`，只读；金币/首层 HP 精确追踪拆到独立测试 Mod `TinyCatTrackerTest`（`tracker-test/`）。界面不再显示“第1层战损：估算”。
- 导出默认中文；“全部导出”按统计界面分页生成 12 个中文 `.xlsx`（同类页面合并），由零依赖 `XlsxWriter` 生成，不使用 Apache POI；`scope=ALL` 时额外把战损梯度表 `怪物危险度.xlsx` 并入同一个包，并在 `图表/` 下生成 `00-完整梯度图`（卡牌+遗物+怪物）与按指标页分图。
- 怪物危险度梯度按幕导出；**不设样本下限**，Boss 5 / 单角色非 Boss 10 / 全角色汇总非 Boss 30 只作参考值，低于参考值的条目照常分档、右下角标注「样本不足 n=X」。名称优先读 `location/chinese_english_mapping.json` 的「怪物名称映射」段。
- 战损统计按实际 Boss 楼层划分幕，跳过宝箱层（`T`）与 Boss 后奖励/过渡层。
- 测试 Mod 0.2.0：写 `runs/{runDir}/{start}.tinycat`（主 Mod 读取）→ 镜像到 `runs_test/{runDir}/`，
  并生成 `*.gold.txt` 金币校验表（每层金币/增减/大额跳变 + `goldGained` 与 `gold_per_floor` 复算的差额）。
  没看到输出时先看 `runs_test/`，再看 `sendToDevs/logs/SlayTheSpire.log` 的 `RunStateTracker VERIFY`。
- 4.1.0 是集成追踪的旧版本；4.0.9 是更早的稳定版，均可作为行为回退参考。
- 修 bug 优先最小改动；涉及统计口径的改动必须先用真实 `.run` 验证，并在回复中说明改了哪些文件。

## 代码结构

```text
src/main/java/tinycat/
├── tinycat.java            模组入口，生命周期与事件订阅
├── RunHistoryManager.java  批量加载历史 .run 文件
├── Localization*.java      中英双语 UI 文案
├── NameMapper.java         卡牌/遗物/事件名 → 中文
├── CardNameNormalizer.java 卡牌名称归一化
├── Utils.java              反射读取游戏私有字段、数值格式化
├── stats/                  Run / RunData / ClassStat / Rate / Mean / WinStreak 等
├── tier/                   Tier List 构建、图片渲染、CSV/HTML/Excel 导出（RankRule=排名型分档 VALUE_QUARTILE，NeowShort=尼奥概况）
├── patches/                StatsScreenPatch（统计界面与导出入口）
└── ui/                     MoreStatsScreen / CharStatRenderer
```

```text
tracker-test/src/main/java/tinycat/tracker/
├── TinyCatTrackerTest.java  独立测试 Mod 入口
└── RunStateTracker.java     写 .tinycat，仅用于校准
```

数据流：RunHistoryPlus 写出 `.run` → `RunHistoryManager` 批量加载 → `Run` 读取可选 `.tinycat` →
`ClassStat` 聚合 → UI 渲染 / `tier` 导出。测试 Mod 独立订阅 `PostDungeonUpdate`，只负责写状态文件。

## 统计口径要点

- **口径定义看 `docs/CORE-LOGIC.md`，变更留痕看 `docs/ALGORITHM-AUDIT.md`**。战损/受到伤害是「每次遭遇一个样本，加和 ÷ 次数」（同局遭遇两次=两个样本）；每幕总战损则是「每局先求和，再按局平均」。改动统计口径前先读这两份文件，改完必须在 AUDIT 的留痕表补一行。
- **幕划分**：`Run.getAct(floor)` 用 `damage_taken` 里的 Boss 楼层动态确定边界；无 Boss 数据时回退 `17/34/52`。战损用 `getActForStats(floor)`，宝箱层（`path_per_floor = "T"`）与 Boss 后一层返回 0 并跳过。改动前先看 `docs/DATA-FLOW.md`。
- **卡牌抓率**：`floor >= 17` 归入第一幕后，与统计页口径一致。
- **金币**：主 Mod 只读 `.tinycat` 的 `gold_gained`（由 `TinyCatTrackerTest` 写入），否则用 `gold_per_floor` 估算；估算时 `goldEstimated=true`，UI/CSV 必须标注。
- **第 1 层 HP**：优先 `.tinycat` 的 `floor0_hp`；否则用第 1 层伤害估算（Ironclad 减 6），`floor0HPEstimated=true`。
- **`.tinycat` 匹配**：`Run` 用最接近 `.run` 结束时间的 `updated_ts`（6 小时内，`start_ts <= 结束时间`）。
- **删牌**：`items_purged` / `purchased_purges` 是商店删牌；其他来源从事件、篝火、Neow、空鸟笼单独统计。

## 编码约定

- 目标 **Java 8**：不要用 `var`、`Stream.toList()`、文本块等 Java 9+ 语法。
- UI 文案必须**中英双语**：`location/uilocalization/strings.json` + `strings-zh.json`。
- 新增统计维度时同步四处：采集（Run）→ 聚合（ClassStat）→ 渲染（StatsScreenPatch/CharStatRenderer）→ 本地化。
- `.run` 读取需兼容 UTF-8/GBK，单文件解析失败不能中断批量加载。
- 注释和日志用中文。
- 不要引入新的第三方依赖，项目依赖游戏自带 JAR 编译。

## 已知限制

1. 主 Mod 不写 `.tinycat`；未安装 `TinyCatTrackerTest` 时，金币/第 1 层 HP 仍是估算。
2. 测试 Mod 的状态文件按时间匹配，结算界面停留超过 6 小时可能匹配失败。
3. `floor0_hp` 只在第 1 层、游戏时间 300 秒内记录；读旧档无法还原。
4. Mod 内容仅靠名称含 `:` 判断；黑名单需手动维护。
5. 没有 JUnit 测试，核心算法用 `scripts/validate_gold_estimate.py` 校验真实数据。
6. 每幕总战损是战斗/事件净损失之和，不抵减篝火回血，不等于净 HP 差。
7. 排名型分档用 `RankRule.JENKS`（Fisher-Jenks 自然断点，k=5，见 `JENKS_K`）。**这是用户定案的口径**：不要改回等人数（会硬拆并列）、不要再自创阈值规则。DP 必须先按不同数值分组再加权，保证同值同档；`best[0][i]` 必须初始化为无穷（否则会产生退化解，S=1/其余全进 D）。
8. 火堆/商店导出**主标写原始次数**（`351 次`），副标写占比 `24.81% · 351/1415`；尼奥主标写**选中次数**，占全部尼奥选择的比例作副标（分母逐条相同）。分档在次数上做，图片大数字必须与分档同源。
9. **版本号 / 发布说明只能由用户决定**：不要自行 bump `build.gradle.kts`、`ModTheSpire.json`、`MOD_VERSION`、渲染器 footer，也不要跳号。改动先留在工作区，README 记在「未发布」段。
10. **商店删牌必须单开**（`商店动作` 表），不能混进「购买商品」：删牌约 1.00 次/店、占商店全部动作 55%，混进去会把所有商品压进 C 档。
11. **火堆/商店页上那个 100% 不是胜率**，是「次数/次数」（`Rate.win` 记次数、`loss` 基本为 0），所以这两个页面做梯度表必须用「次数 ÷ 该幕总数」的占比，不能用页面百分比。
12. 尼奥：`neow_bonuses_skipped_log` 不带代价、`neow_costs_skipped_log` 恒为 `NONE`，**逐 (奖励,代价) 的「提供次数」无法还原**，所以不做逐条选取率。表格与导出按 `(bonus,cost)` 一行一条、按选中次数排名，占比分母是「全部尼奥选择次数」；短名统一由 `tier/NeowShort.java` 生成（掉血删2 / 血上限删2 / 诅咒250 / 掉钱250）。
13. 卡图按名字解析依赖游戏自带 `localization/eng/cards.json`（英文名→cardID）；该资源不可用时退回内置 19 条旧 ID 表。**别用 `AbstractCard.name` 当英文键**——中文客户端下它是中文。
14. 火堆升级偏好 = 该幕升级次数 ÷ 到该幕结束累计拥有张数，只统计火堆 SMITH；已升级副本不会再次被敲，第二幕起升级率下降是口径特性。拥有张数含初始牌/奖励/商店/事件/变牌，扣除删牌/变牌移出；变牌去向和空鸟笼删牌在 `.run` 里没有记录，误差集中在 Strike/Defend。拥有 < 10 不参与排名。
