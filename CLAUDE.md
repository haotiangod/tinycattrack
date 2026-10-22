# TinyCat — 项目规则

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
- 产物：`build/libs/TinyCat_V4.1.1.jar`；测试 Mod 为 `build/libs/TinyCatTrackerTest_V0.1.0.jar`。
- 版本号同时改 `build.gradle.kts` 的 `versionTag`、`src/main/resources/ModTheSpire.json` 的 `version`；测试 Mod 版本在 `tracker-test/src/main/resources/ModTheSpire.json`。

## 当前状态

- 版本 **4.1.1**：主 Mod 不再写 `.tinycat`，只读；金币/首层 HP 精确追踪拆到独立测试 Mod `TinyCatTrackerTest`（`tracker-test/`）。
- 战损统计按实际 Boss 楼层划分幕，跳过宝箱层（`T`）与 Boss 后奖励/过渡层。
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
├── tier/                   Tier List 构建、图片渲染、CSV/HTML 导出
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
