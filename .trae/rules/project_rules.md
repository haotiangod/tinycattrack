# TinyCat — 项目规则

《杀戮尖塔》(Slay the Spire) 的本地数据统计 Mod。Java 8 + Gradle 8.0（Kotlin DSL）+ ModTheSpire 生态。

## 构建环境（已配置好，不要改动这些路径）

| 组件 | 路径 |
|---|---|
| JDK 8 (Temurin 8u504-b01) | `C:\Users\MI\.workbuddy\binaries\jdk\jdk8u504-b01` |
| Gradle 8.0 | `C:\Users\MI\.workbuddy\binaries\gradle\gradle-8.0` |
| Python 3.12.10 | `C:\Users\MI\.workbuddy\binaries\python\versions\3.12.10\python.exe` |
| 游戏本体 | `C:\Program Files (x86)\Steam\steamapps\common\SlayTheSpire` |
| 依赖 JAR | `C:\Program Files (x86)\Steam\steamapps\workshop\content\646570\<id>\` |

- **必须用 JDK 8 编译**，目标 Java 8 字节码。不要用 JDK 17+ 直接编译。
- Steam 装在 **C 盘**，`build.gradle.kts` 原配置指向 D 盘（已修正）。
- 构建：`gradlew buildJAR` 或双击 `build-local.bat`。产物 `build/libs/TinyCat_V4.0.9.jar`。

## 版本现状（重要）

- **v10 稳定，用户在用。v11 有 bug，未安装。**
- 当前源码 `ModTheSpire.json` 标注 4.0.11，属未验证版本。
- **不要擅自回退或大改稳定逻辑。** 修复请做最小改动并说明改动位置。

## 代码结构

```
src/main/java/tinycat/
├── tinycat.java            模组入口，事件订阅
├── RunHistoryManager.java  批量加载历史 .run 文件
├── RunData.java            历史对局集合，排序/增量加载
├── Localization*.java      中英双语 UI 文案
├── NameMapper.java         卡牌/遗物/事件名 → 中文
├── Utils.java              反射读游戏私有字段、数值格式化
├── stats/                  Run / ClassStat / Rate / Mean / WinStreak
├── patches/                StatsScreenPatch（挂钩游戏统计界面）
└── ui/                     MoreStatsScreen / CharStatRenderer
```

数据流：游戏事件 → `tinycat.java` 订阅 → `Run` 采集 → `.run` 文件 →
`RunHistoryManager` 加载 → `ClassStat` 聚合 → UI 渲染。

## 已知 Bug（已定位，未修复）

- **P0** `tinycat.java:50` `capturedFloor0HP` 为 `public static` 且从不重置，`Run.java:510` 的 `getFloor0HP()` 将其作为优先级 1 返回 → 批量加载历史时全部对局第 1 层战损被污染。伴生文件补偿机制也失效（写入用开局时间戳，读取用结束时间戳）。
- **P1** `ClassStat.java:682` deckSizeStats 放在 `eventStats()` 末尾导致漏统计；`Run.java:74`/`RunHistoryManager.java:62` 缺判空与 try-catch；`StatsScreenPatch.java:537-552` actNumber 恒为 0 导致问号统计不显示；`ClassStat.java:998 vs 1019` 商店删牌口径不一致。
- **P2** `ClassStat.java:833` 遗留 `System.out.println`；`MoreStatsScreen.java:148` 无条件计算 5 份 ClassStat；`StatsScreenPatch.java:698` HashMap 每帧重建。
- **P3** `StatsScreenPatch.java:101` 角色配色硬编码英文，中文客户端失效；`Utils.java:141` `round()` 误用 `MathContext(3)`（有效数字≠小数位）。
- **P4** `RunData.orderByTimestampDesc` 用字符串比较时间戳；`Utils.getField` 取不到字段时抛 RuntimeException；`getIncrementalRuns` 空列表越界。

## 编码约定

- 目标 **Java 8**：禁用 var、`Stream.toList()`、文本块等 Java 9+ 语法。
- UI 文案必须**中英双语**：`strings.json` + `strings-zh.json` 同步加。
- 新增统计维度要同步四层：采集(Run) → 聚合(ClassStat) → 渲染(StatsScreenPatch/CharStatRenderer) → 本地化。只加一半会出现「算了却没显示」。
- 注释和日志用中文。不引入新第三方依赖。
