# TinyCat — 项目规则

《杀戮尖塔》(Slay the Spire) 的本地数据统计 Mod。Java 8 + Gradle 8.0（Kotlin DSL）+ ModTheSpire 生态。

## 构建环境（已配置好，不要改动这些路径）

| 组件 | 路径 |
|---|---|
| JDK 8 (Temurin 8u504-b01) | `C:\Users\MI\.workbuddy\binaries\jdk\jdk8u504-b01` |
| Gradle 8.0 | `C:\Users\MI\.workbuddy\binaries\gradle\gradle-8.0` |
| Python 3.12.10 | `C:\Users\MI\.workbuddy\binaries\python\versions\3.12.10\python.exe` |
| 游戏本体 | `C:\Program Files (x86)\Steam\steamapps\common\SlayTheSpire` |
| 依赖 JAR (ModTheSpire / BaseMod / RunHistoryPlus) | `C:\Program Files (x86)\Steam\steamapps\workshop\content\646570\<id>\` |

- **必须用 JDK 8 编译**，目标是 Java 8 字节码。不要用 JDK 17+ 直接编译。
- Steam 装在 **C 盘**，`build.gradle.kts` 原配置指向 D 盘（已修正）。
- 构建命令：`gradlew buildJAR`（或双击 `build-local.bat`）。
- 产物：`build/libs/TinyCat_V4.0.9.jar`。

## 版本现状（重要）

- **v10 是稳定版本，用户实际在用的就是这个。**
- **v11 有 bug，用户没有安装。** 当前源码 `ModTheSpire.json` 标注 4.0.11，属于尚未验证的版本。
- **不要擅自把稳定逻辑回退或大改。** 修 bug 时优先做最小改动，改完说明改了哪个文件、哪几行。

## 代码结构

```
src/main/java/tinycat/
├── tinycat.java          模组入口，ModTheSpire 初始化 + 事件订阅
├── RunHistoryManager.java  批量加载历史 .run 文件
├── RunData.java         历史对局集合，排序/增量加载
├── Localization*.java   中英双语 UI 文案
├── NameMapper.java      卡牌/遗物/事件名 → 中文
├── Utils.java           反射读取游戏私有字段、数值格式化
├── stats/               Run / ClassStat / Rate / Mean / WinStreak
├── patches/             StatsScreenPatch（往游戏统计界面挂钩）
└── ui/                  MoreStatsScreen / CharStatRenderer
```

数据流：游戏事件 → `tinycat.java` 订阅 → `Run` 采集 → 序列化到 `.run` 文件 →
`RunHistoryManager` 批量加载 → `ClassStat` 聚合 → UI 渲染。

## 已知 Bug（已定位，未修复）

**P0 · 静态字段污染全部历史战损**
`tinycat.java:50` 的 `capturedFloor0HP` 是 `public static`，在 `receivePostDungeonInitialize()` 里被赋成**当前这一局**的血量且**从不重置**；
`Run.java:510` 的 `getFloor0HP()` 把它作为优先级 1 返回。
`RunHistoryManager.refreshData()` 批量加载历史时每局都读到同一个值 → 打完一局后所有历史对局的第 1 层战损全错。
伴生文件补偿机制也失效：写入用 `System.currentTimeMillis()/1000`（开局时刻），读取用 `runData.timestamp`（结束时刻），文件名永远对不上。

**P1 · 数据计算**
- `ClassStat.java:682` `deckSizeStats(run)` 被塞在 `eventStats()` 末尾，而该方法开头 `if (run.eventStats == null) return;` → 事件数据缺失的局会漏统计平均卡组大小/平均分/平均金币。
- `Run.java:74`、`RunHistoryManager.java:62` 缺判空和 try-catch，任一损坏 `.run` 文件会让整个 stream 中断。
- `StatsScreenPatch.java:537-552` 死亡率页 actNumber 恒传 0 → 问号节点统计不显示。
- `ClassStat.java:998 vs 1019` 商店删牌总数用 `purchasedPurges`、按幕用 `itemsPurgedFloors`，口径不一致。

**P2 · 性能**
- `ClassStat.java:833` 遗留 `System.out.println` 调试输出。
- `MoreStatsScreen.java:148` `getClassStats()` 无论选哪个角色都算 5 份完整 ClassStat。
- `StatsScreenPatch.java:698` `actionNames` HashMap 建在渲染 lambda 内部，每帧重建。

**P3 · 显示与国际化**
- `StatsScreenPatch.java:101` `getCharColor()` 硬编码英文 `"Ironclad"/"Silent"`，中文客户端下角色配色恒为黄色。
- `renderShopAct`/`renderCampfireAct` 把计数当胜率渲染。
- `Utils.java:141` `round()` 误用 `MathContext(3)`（有效数字 ≠ 小数位）。

**P4 · 隐患**
`RunData.orderByTimestampDesc` 用字符串比较时间戳；`Utils.getField` 取不到私有字段时抛 `RuntimeException`；
`hasEmptyCage()` 硬编码英文 `"Empty Cage"`；`getIncrementalRuns` 日期列表为空时 `dates.get(0)` 越界。

## 编码约定

- 目标 **Java 8**：不要用 var、Stream.toList()、文本块等 Java 9+ 语法。
- UI 文案必须**中英双语**都加，走 `Localization` / `strings.json` + `strings-zh.json`。
- 新增统计维度时，记得同步：采集（Run）→ 聚合（ClassStat）→ 渲染（StatsScreenPatch/CharStatRenderer）→ 本地化。只加一半会出现「算了却没显示」。
- 注释和日志用中文。
- 不要引入新的第三方依赖，这个项目靠游戏自带 JAR 编译。
