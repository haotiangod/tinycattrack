# 金币与第 0 层 HP 追踪 / StateNotes 许可证评估

> 2026-10-22 对 `StateNotes-0.4.0-local-test.zip` 做了许可证与数据能力评估。
> 最终结论：**TinyCat 不需要 StateNotes 或 STSStateSaver，也没有使用、复制或再分发它们的任何代码。**

## 1. 许可证结论

- `State Notes` 项目自有代码：MIT（Copyright (c) 2026 CYZ）。
- 内嵌的 `STSStateSaver / SaveStateMod 1.3.0`（Board Engineer）：
  - 包内 `NOTICE.txt` 写明上游没有 LICENSE/COPYING/NOTICE，也没有再分发授权。
  - 上游 issue #13 正在请求授权：<https://github.com/boardengineer/STSStateSaver/issues/13>
- 因此不能把 `STS1StateNotes.jar` 或 `savestate.*` 打进 TinyCat 的 JAR、release 或导出包。“免费/非商业”不能替代许可证。

## 2. 为什么不需要它

评估时确认了两个游戏原生数据源，足以覆盖需求：

| 数据 | 原生来源 | 精度 |
|---|---|---|
| 累计获得金币 | `CardCrawlGame.goldGained`（`public static int`） | 精确，随存档重置/恢复 |
| 当前金币 | `AbstractPlayer.gold` | 精确 |
| 当前 HP / 楼层 | `AbstractPlayer.currentHealth`、`AbstractDungeon.floorNum` | 精确 |
| 开局第 1 层 HP | 开局后 `PostDungeonUpdate` 时读取 `currentHealth` | 精确（未经过战斗） |

StateNotes 的逐回合快照只能看到“当前金币”，无法单独还原累计获得金币；而 `goldGained` 本身就是累计值。因此接入 StateNotes 既无必要，也会引入许可证风险。

原计划中的“联系上游作者”“无许可证时绕过 save 类 Mod”均不再需要。

## 3. 实现（4.2.1：主 Mod 只读，测试 Mod 写入）

主 TinyCat 不订阅 `PostDungeonUpdate`、不写任何状态文件；精确状态由独立的 `TinyCatTrackerTest` 测试 Mod 生成。测试 Mod 实现 `PostDungeonUpdateSubscriber`，每帧检查，仅在楼层或 `goldGained` 变化时写文件：

```text
runs/{runDir}/{start_ts}.tinycat
version=1
start_ts=<开局 Unix 秒>
floor0_hp=<开局第 1 层 HP，无法确定时为 -1>
start_gold=<开局金币，无法确定时为 -1>
gold_gained=<累计获得金币>
gold=<当前剩余金币>
hp=<当前 HP>
floor=<当前楼层>
updated_ts=<最后写入 Unix 秒>
```

- 只在第 1 层且 `CardCrawlGame.playtime < 300` 时记录 `floor0_hp`，避免把中途读档的 HP 当成开局 HP。
- 楼层刷新后，用 `now - playtime` 在 1 小时窗口内寻找最近的 `.tinycat` 重新挂接，避免重复创建。
- `Run` 加载历史时，用 `updated_ts` 与 `.run` 结束时间匹配（6 小时窗口，要求 `start_ts <= 结束时间`）。
- 没有状态文件时回退到原有估算，并保留 `goldEstimated` / `floor0HPEstimated` 标记。
- 主 TinyCat 只在发现 `.tinycat` 时读取；普通安装不会生成文件，也不会与其它 Mod 争抢写入。
- 测试 Mod 与主 Mod 的 modid 不同，可同时安装。构建：`gradle buildTrackerTestJAR`，产物 `build/libs/TinyCatTrackerTest_V0.1.0.jar`。

## 4. 金币估算准确度

在 1,113 局真实数据中，305 局可从分数明细（“下钱雨啦”“好多好多钱”等）提取精确累计金币，作为真值校验估算法：

| 指标 | 数值 |
|---|---|
| 平均绝对误差 | 38.0 |
| 中位绝对误差 | 39 |
| P90 绝对误差 | 64 |
| 最大绝对误差 | 160 |
| 带符号平均误差 | -2.5 |

估算器系统偏差很小，但单局误差可达 160，因此装了测试 Mod 的新对局应优先使用 `.tinycat` 的精确值。

验证脚本：`python scripts/validate_gold_estimate.py`。脚本还会按实际 Boss 楼层统计每幕范围，并审计宝箱层/Boss 后过渡层上是否仍有战斗或事件。

## 5. 分发边界

- 主 TinyCat 不新增任何 StateNotes/STSStateSaver 编译依赖，主 JAR 不包含状态追踪代码。
- 测试 Mod `TinyCatTrackerTest` 是本项目自有代码，仅写入 `.tinycat`，不引用 `savestate` 包。
- 不复制 `savestate` 包，不在主 JAR、测试 JAR 或导出包中包含其代码。
- `.tinycat` 是 TinyCat 自己生成的纯文本文件，不包含第三方代码。
