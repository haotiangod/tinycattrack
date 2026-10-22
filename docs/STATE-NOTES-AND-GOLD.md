# State Notes / 金币与 StateNotes 接入评估

> 本文件记录 2026-10-22 对 `StateNotes-0.4.0-local-test.zip` 的许可证与数据能力评估。
> 结论：**不要把 StateNotes/STSStateSaver 的代码或 JAR 打进 TinyCat 或导出包。**

## 1. 许可证结论

- `State Notes` 项目自有代码：MIT（Copyright (c) 2026 CYZ）。
- 内嵌的 `STSStateSaver / SaveStateMod 1.3.0`（Board Engineer）：
  - 包内 `NOTICE.txt` 明确写明：上游没有 LICENSE/COPYING/NOTICE，也没有再分发授权。
  - 上游 issue #13 正在请求授权：<https://github.com/boardengineer/STSStateSaver/issues/13>
- 因此：
  - TinyCat 可以按 MIT 使用 State Notes **自己**的代码（保留版权/许可声明），但**不能**把内嵌的 `savestate.*` 一起再分发。
  - 不能把 `STS1StateNotes.jar` 放进 TinyCat 的 release/zip。
  - “免费/非商业”不是许可证，不能替代授权。

## 2. 它能提供什么

- `savestate.CreatureState` / `PlayerState` 有 `gold` 当前值。
- `sts1state.Snapshot` 会记录 `gold`、`currentHealth`、`maxHealth` 等快照字段。
- `sts1state.Recorder` 写出 `events.jsonl`、`run.json`、`segment-*.json`，位于它的本地数据目录。
- 这些是**当前/逐回合状态**，不是“整局累计获得金币”。累计获得金币还需要金币获取事件，或结合购买/事件花费还原。

## 3. 金币口径

TinyCat 当前 `Run.parseGold()`：

1. 优先读 `runs/gold_track/{runDir}/{timestamp}.txt` 的 `gold_own`（可选 gold-tracker）。
2. 否则用 `gold_per_floor` 估算：
   - 第一层金币 - Neow 开局扣除；
   - 加上后续正增量；
   - MawBank +12。
3. 这个值是**累计获得金币**的估算，不是最终剩余金币。

可选方向：

- **最终剩余金币**：`.run` 的 `gold` 字段已经是精确值，不需要 StateNotes；可直接在 `Run` 中新增/使用最终金币字段。
- **累计获得金币**：需要“获得金币”的事件流。StateNotes 的逐回合 `gold` 只能看当前值，不能单独还原累计获得；需要额外事件/购买花费数据。
- **可选只读适配**：TinyCat 检测到用户安装 StateNotes 后，只读它的本地 JSON；不打包、不修改、不再分发。必须先获得作者对字段稳定性和读取行为的确认。

## 4. 联系作者的建议问题

```text
你好，我是 TinyCat（MPL-2.0 本地统计 mod）的作者。
我想以“可选、只读”的方式读取 State Notes / STSStateSaver 的本地快照，
用于精确记录每层 HP 和金币，不修改、不打包、不再分发你们的代码。

想确认：
1) 是否允许 TinyCat 读取你们导出的本地 JSON？
2) STSStateSaver 目前没有许可证，是否可以提供 MIT/Apache 许可或书面授权？
3) 是否有稳定的字段/schema（floor、gold、currentHealth）？
```

## 5. 在授权前的实现边界

- 不新增对 StateNotes 的编译依赖。
- 不复制 `savestate` 包。
- 不在导出包中包含 StateNotes 的 jar/代码。
- 最多只在文档/README 中说明“可选安装 StateNotes 后可获得更精确数据”，不自动读取。
