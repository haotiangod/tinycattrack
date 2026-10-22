# TinyCat - 杀戮尖塔本地数据统计模组

纯本地离线运行数据追踪与统计，不依赖任何外部服务器。

> 基于 [MaT1g3R/sts-stat-tracker](https://github.com/MaT1g3R/sts-stat-tracker)（MPL-2.0）修改。

---

## 基本信息

| 项目 | 内容 |
|------|------|
| ModID | `TinyCat` |
| 版本 | 4.0.9 |
| 作者 | tinycat |
| 依赖 | BaseMod, RunHistoryPlus |
| 许可证 | MPL-2.0 |

---

## 项目结构

```
tinycat/
├── src/main/java/tinycat/
│   ├── tinycat.java              # 模组入口，初始化与生命周期管理
│   ├── Config.java               # SpireConfig 配置管理
│   ├── Localization.java         # 本地化（中/英）管理
│   ├── LocalizationKeys.java     # 本地化键定义
│   ├── NameMapper.java           # 中英文名称映射
│   ├── RunHistoryManager.java    # 运行历史数据加载与管理
│   ├── Utils.java                # 工具函数
│   ├── YearMonth.java            # 年月日期工具
│   ├── patches/
│   │   ├── AchievementGridPatch.java  # 成就界面补丁
│   │   ├── SaveAndContinuePatch.java  # 存档续玩补丁
│   │   └── StatsScreenPatch.java      # 统计界面补丁
│   ├── stats/
│   │   ├── BossRelic.java        # Boss 遗物统计数据
│   │   ├── Card.java             # 卡牌统计数据
│   │   ├── CardChoice.java       # 选牌数据
│   │   ├── ClassStat.java        # 职业统计聚合（核心统计模型）
│   │   ├── EncounterStats.java   # 战斗遭遇统计
│   │   ├── Leaderboard.java      # 排行榜
│   │   ├── Mean.java             # 平均值计算
│   │   ├── MetaScaling.java      # 元成长统计
│   │   ├── Neow.java             # Neow 奖励统计
│   │   ├── NeowBonusLog.java     # Neow 奖励日志
│   │   ├── PurgeSource.java      # 删牌来源枚举
│   │   ├── Rate.java             # 频率/比率统计
│   │   ├── Run.java              # 单次对局数据
│   │   ├── RunData.java          # 对局原始数据
│   │   ├── SampleSize.java       # 样本量管理
│   │   ├── ShopContent.java      # 商店内容统计
│   │   └── WinStreak.java        # 连胜统计
│   └── ui/
│       ├── CharStatRenderer.java # 角色统计渲染
│       └── MoreStatsScreen.java  # 扩展统计界面
├── src/main/resources/
│   ├── tinycat/img/              # 图标资源
│   └── ModTheSpire.json          # ModTheSpire 元数据
├── location/                     # 本地化与映射资源
│   ├── uilocalization/           # UI 字符串本地化（中/英）
│   └── chinese_english_mapping.json  # 中英文术语映射表
├── resource/                     # 参考资源（原 StatsTracker 项目）
├── build.gradle.kts              # Gradle 构建脚本（Kotlin DSL）
├── build-local.bat               # 本地构建快捷脚本
└── verify_purge_logic_v2.py      # 删牌统计逻辑校验脚本
```

---

## 功能模块

### 1. 对局数据采集
- 自动记录每次对局的完整数据，保存为 `.run` 文件（JSON 格式）
- 数据按存档位和角色自动分目录存储（如 `runs/IRONCLAD/`、`runs/1_DEFECT/`）
- 支持多存档位独立追踪

### 2. 职业统计面板
- **总体统计**：胜率、死亡数、游戏时间、最高分、最高连胜等
- **卡牌统计**：选牌率（Act1 / After Act1）、卡牌胜率
- **Neow 奖励统计**：各选项的选取率与胜率
- **Boss 遗物统计**：各遗物选取率与胜率（按 Act 分类，含交换 Boss 遗物）
- **战斗遭遇统计**：各敌人死亡率、精英生存率
- **事件统计**：各事件胜率（按 Act 分类）
- **遗物统计**：遗物胜率、商店购买遗物胜率
- **删牌统计**：按来源分类（商店/事件/Neow/篝火/空鸟笼），按 Act 分类
- **问号节点统计**：问号数量与事件数量（按 Act 分类）
- **火堆行动统计**：各行动类型统计（升级按卡牌明细，其他按类型）
- **商店行动统计**：购买卡牌、遗物、药水、删牌等明细
- **伤害统计**：平均承受伤害、实际 HP 损失（含/不含治疗）
- **Tier List 导出**：在「第一幕卡牌选取率 / 第一幕后卡牌选取率 / BOSS 遗物选取率」页面右上角一键导出 CSV + JSON + HTML + 整图（tierlist.jpg/png）+ 卡图/遗物图；图片使用本地缓存，重复导出不再反复提取（全部取自本地游戏，不依赖外部图源）

### 3. 数据筛选
- 按日期范围筛选
- 按角色筛选（铁甲战士/静默猎人/缺陷机器人/观者/全部）
- 按统计类型切换查看
- 包含/排除放弃对局
- 最小样本量过滤

### 4. 本地化
- 完整的中文/英文双语支持
- UI 字符串通过 `uilocalization/` 下的 JSON 文件管理
- 术语映射表支持中英文名称互转

---

## 构建与安装

### 前置条件
- JDK 8
- Gradle 8.0+
- 杀戮尖塔游戏安装
- ModTheSpire + BaseMod + RunHistoryPlus

### 构建
```bash
# Windows
build-local.bat

# 或手动
gradle buildJAR
```

构建产物输出到 `build/libs/TinyCat_V{version}.jar`。

### 安装
将构建的 JAR 文件复制到杀戮尖塔的 `mods/` 目录下即可。

---

## 工具脚本

| 脚本 | 用途 |
|------|------|
| `verify_purge_logic_v2.py` | 校验删牌统计数据是否与对局记录一致 |
| `format_json.py` | 智能格式化 JSON 文件（空数组紧凑显示） |

---

## 未来扩展展望

### 短期（v4.1.x）
- [ ] **数据导出**：支持将对局数据导出为 CSV，方便外部分析
- [ ] **自定义统计维度**：允许用户自定义关注的统计指标和排序方式
- [ ] **对局回放摘要**：生成对局关键节点的时间线视图（Boss 战、关键事件、遗物获取）

### 中期（v4.2.x）
- [ ] **跨存档位聚合**：支持将多个存档位的对局数据合并统计
- [ ] **趋势图表**：以折线图/柱状图展示胜率、平均分等指标的时间趋势
- [ ] **卡牌评分系统**：基于胜率与选牌率自动计算卡牌评分
- [ ] **路线分析**：统计各楼层节点的选择分布与胜率关联

### 长期（v5.0.x）
- [ ] **对局对比**：支持选择两次对局进行详细对比（路线、选牌、遗物）
- [ ] **种子分析**：支持按种子查询对局，分析特定种子的最优路线
- [ ] **社区数据共享**：可选匿名上传统计数据，构建社区统计数据库
- [ ] **AI 辅助分析**：基于 LLM 对统计数据生成自然语言分析报告
- [ ] **Web 面板**：搭建本地 Web 服务，通过浏览器查看统计面板（端口复用 `resource/` 中的 Go 后端参考代码）

---

## 开发者指南

### 数据流架构

```
RunHistoryPlus (.run JSON 文件)
  │  runs/{角色名}/ 或 runs/{存档位}_{角色名}/
  │
  ▼
RunData (Gson 反序列化，原始 JSON 字段)
  │
  ▼
Run (单次对局模型)
  │  解析楼层路径、衍生数据（第0层HP、金币、战损）
  │  过滤无效对局（非A20、自定义种子、每日挑战等）
  │
  ▼
RunHistoryManager (按日期/角色分组管理 Run 列表)
  │
  ▼
ClassStat.StatCollector (内部聚合类)
  │  遍历 Run 列表，聚合各维度数据：
  │  - 卡牌选取率/胜率（按 Act1 / After Act1）
  │  - Neow 奖励选取率/胜率
  │  - Boss 遗物选取率/胜率（按 Act，含交换）
  │  - 战斗遭遇 HP 损失/死亡率/药水使用
  │  - 事件胜率（按 Act）
  │  - 删牌来源（商店/事件/Neow/篝火/空鸟笼）
  │  - 火堆/商店行动明细
  │  - 元成长极限值
  │
  ▼
ClassStat (公开字段，供 UI 读取)
  │
  ▼
StatsScreenPatch → MoreStatsScreen → CharStatRenderer
  │  根据用户选择的统计类型、角色、日期范围
  │  从 ClassStat 读取对应字段并渲染到屏幕
  │
  ▼
游戏内统计界面
```

### 关键设计决策

| 决策 | 原因 |
|------|------|
| 第0层HP精确捕获 | 游戏原版 StatsTracker 只记录最高HP，不记录第0层初始HP，导致战损计算不准确。通过 `PostDungeonInitialize` 补丁在开局时捕获第0层HP，存入 SpireConfig。 |
| 金币数据双路径 | gold-tracker 模组提供精确的金币追踪文件，但并非所有用户都安装。回退方案使用 `gold_per_floor` 公式估算（RMSE最小化常数），在 UI 中不做区分。 |
| 过滤非A20对局 | 高进阶玩家社群主要关注 A20 数据，低进阶和新手模式的统计偏差较大，默认排除。 |
| 过滤 Mod 卡牌/遗物 | 名称中包含 `:` 的卡牌/遗物基本来自 Mod 内容，排除以避免统计偏差。局限性：无法检测名称不含 `:` 的 Mod 内容。 |
| 中英文双语设计 | 通过 `Localization` 类管理，UI 字符串在 JSON 文件中，术语映射在 `chinese_english_mapping.json` 中，支持双向查找。 |
| 懒加载 UI 字符串 | `MoreStatsScreen.getClasses()` 和 `getStatTypes()` 使用懒加载，因为 `Localization.initialize()` 调用时 `Settings.language` 可能为 null，需要等到首次 `get()` 时才确定语言。 |

### 如何添加新统计维度

以添加"药水使用统计"为例：

1. **在 `ClassStat` 中添加字段**
   ```java
   // ClassStat.java
   public List<Rate<String>> potionUsageRates = new ArrayList<>();
   ```

2. **在 `StatCollector` 中聚合数据**
   ```java
   // ClassStat.StatCollector
   Map<String, Rate<String>> potionMap = new HashMap<>();
   // 遍历 Run.potionUsePerFloor，累加各药水使用次数
   // 在 finalise() 中排序并放入 stat.potionUsageRates
   ```

3. **在 `StatsScreenPatch` 中添加下拉菜单选项和渲染**
   ```java
   // StatsScreenPatch.java
   // 在 statTypeDropdown 中添加 "POTION" 选项
   // 添加 renderPotionUsageRates(SpriteBatch sb, ...) 方法
   // 在 renderStatScreen() 中调用
   ```

4. **在 `Localization` 语言文件中添加文本**
   ```json
   // uilocalization/strings-zh.json
   { "stat.potion_usage": "药水使用统计" }
   // uilocalization/strings.json
   { "stat.potion_usage": "Potion Usage Stats" }
   ```

5. **更新 `LocalizationKeys` 常量**（可选，如需要程序化引用）

### 依赖版本要求

| 依赖 | 最低版本 | 说明 |
|------|---------|------|
| BaseMod | 5.x | 提供 `PostInitializeSubscriber` 等订阅接口 |
| RunHistoryPlus | 最新版 | 提供 `.run` 文件生成，以及 `neow_bonus_log`、`potion_use_per_floor`、`shop_contents` 等扩展字段 |
| ModTheSpire | 3.x | 模组加载框架 |

### 已知限制

1. **金币估算**：未安装 gold-tracker 时，金币数据为公式估算值而非精确值，商店购买明细可能不准确
2. **Mod 卡牌检测**：仅通过名称中是否包含 `:` 来判断 Mod 内容，可能漏判或误判
3. **第0层HP**：Ironclad 的 Burning Blood 回血 6 为硬编码，若游戏更新改变此数值，估算法将失效
4. **硬编码黑名单**：`Run.java` 中的 `bannedCards`、`bannedRelics` 等列表需手动维护，新 Mod 内容可能未被过滤
5. **无自动测试**：核心算法（金币估算、HP估算、连胜计算）缺少单元测试覆盖

---

## 更新日志

### v4.0.9 (当前)
- 新增 Tier List 导出（CSV + JSON + HTML + tierlist.jpg/png 整图 + 卡图/遗物图，全部取自本地游戏）
- Tier List 图片增加本地缓存，重复导出不再反复提取；整图 JPEG 便于直接发送
- 统一卡牌旧 ID 名称映射，修复火堆/商店页与卡牌页命名口径不一致
- .run 读取增加 UTF-8/GBK 严格解码回退，避免非中文 locale 下批量解析失败
- 修复第 0 层 HP 静态字段污染历史战损的问题（批量加载时隔离）
- 修复中文客户端角色配色恒为黄色的问题
- 修复删牌统计逻辑，完善 `PurgeSource` 分类
- 新增问号节点与事件统计
- 新增火堆行动统计（升级明细 + 行动类型）
- 新增商店行动统计（购买明细）
- 新增伤害统计（HP 损失分析）
- 优化中英文名称映射

### v4.0.8
- 修复空鸟笼删牌计数
- 改进 Neow 奖励日志记录

### v4.0.0
- 从 StatsTracker 重构为 TinyCat
- 纯离线模式，移除所有网络相关代码
- 添加完整的中文本地化支持