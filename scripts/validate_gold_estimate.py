#!/usr/bin/env python3
"""
TinyCat 金币/首层 HP 与每幕楼层归属校验。

对应 Java:
- Run.parseGold()           累计获得金币：优先 .tinycat，缺失时按 gold_per_floor 估算
- Run.getFloor0HP()         第 1 层开始前 HP：优先 .tinycat，缺失时按第 1 层伤害反推
- Run.getAct(int)           按本局实际 Boss 楼层划分每幕

数据来源:
- runs/{角色}/*.run          RunHistoryPlus 对局记录
- runs/{角色}/*.tinycat      TinyCat 自行记录的状态（v4.1.0+）

精确金币:
少数 .run 的 score_breakdown 会记录金币成就的实际数值，例如
  好多好多钱 (1178): 25
  下钱雨啦 (2015): 50
该数值就是本局累计获得金币，可用于校验估算公式。

用法:
  python scripts/validate_gold_estimate.py
  python scripts/validate_gold_estimate.py --csv gold_report.csv
"""

import argparse
import csv
import glob
import json
import os
import re
import statistics
import sys
from collections import Counter, defaultdict

DEFAULT_RUNS = r"C:\Program Files (x86)\Steam\steamapps\common\SlayTheSpire\runs"

DEDUCTION = {
    "TWO_FIFTY_GOLD": 55,
    "HUNDRED_GOLD": 40,
    "RANDOM_COMMON_RELIC": 35,
    "REMOVE_CARD": 40,
    "ONE_RARE_RELIC": 45,
    "REMOVE_TWO": 40,
    "TRANSFORM_TWO_CARDS": 55,
    "TRANSFORM_CARD": 60,
    "UPGRADE_CARD": 60,
    "THREE_SMALL_POTIONS": 45,
    "RANDOM_COLORLESS_2": 60,
    "THREE_RARE_CARDS": 45,
    "THREE_ENEMY_KILL": 40,
}

ACT1_BOSSES = {"Hexaghost", "Slime Boss", "The Guardian"}
ACT2_BOSSES = {"Automaton", "Collector", "Champ"}
ACT3_BOSSES = {"Time Eater", "Awakened One", "Donu and Deca"}

GOLD_SCORE = re.compile(
    r"(?:好多好多钱|下钱雨啦|我喜欢金币|Money Money|Raining Money|I Like Gold)\s*\((\d+)\)"
)


def read_json(path):
    raw = open(path, "rb").read()
    for encoding in ("utf-8", "gbk", "utf-8-sig"):
        try:
            return json.loads(raw.decode(encoding))
        except (UnicodeDecodeError, json.JSONDecodeError):
            continue
    return None


def estimate_gold(j):
    floors = j.get("gold_per_floor") or []
    if not floors:
        return 0
    start = 0 if j.get("neow_cost") == "NO_GOLD" else DEDUCTION.get(j.get("neow_bonus"), 45)
    total = max(0, floors[0] - start)
    for previous, current in zip(floors, floors[1:]):
        if current > previous:
            total += current - previous
    if "MawBank" in (j.get("relics") or []):
        total += 12
    return total


def exact_gold(j):
    for line in j.get("score_breakdown") or []:
        match = GOLD_SCORE.search(line)
        if match:
            return int(match.group(1))
    return None


def boss_floors(j):
    floors = {1: [], 2: [], 3: []}
    for battle in j.get("damage_taken") or []:
        name = battle.get("enemies")
        if name in ACT1_BOSSES:
            floors[1].append(int(battle["floor"]))
        elif name in ACT2_BOSSES:
            floors[2].append(int(battle["floor"]))
        elif name in ACT3_BOSSES:
            floors[3].append(int(battle["floor"]))
    act1 = min(floors[1]) if floors[1] else None
    act2 = min(floors[2]) if floors[2] else None
    act3 = max(floors[3]) if floors[3] else None
    return act1, act2, act3


def act_of(floor, act1, act2, act3):
    if act1:
        if floor <= act1:
            return 1
        if not act2:
            return 2
        if floor <= act2:
            return 2
        if not act3:
            return 3
        if floor <= act3:
            return 3
        return 4
    if floor <= 17:
        return 1
    if floor <= 34:
        return 2
    if floor <= 52:
        return 3
    return 4


def read_tinycat(path):
    state = {}
    try:
        with open(path, encoding="utf-8", errors="replace") as handle:
            for line in handle:
                key, sep, value = line.strip().partition("=")
                if sep:
                    state[key] = value
    except OSError:
        return None
    try:
        return {
            "start_ts": int(state.get("start_ts", -1)),
            "updated_ts": int(state.get("updated_ts", -1)),
            "floor0_hp": int(state.get("floor0_hp", -1)),
            "gold_gained": int(state.get("gold_gained", -1)),
            "floor": int(state.get("floor", -1)),
        }
    except ValueError:
        return None


def match_tinycat(run_timestamp, sidecars):
    best = None
    best_diff = None
    for sidecar in sidecars:
        if sidecar["start_ts"] < 0 or sidecar["updated_ts"] < 0:
            continue
        if sidecar["start_ts"] > run_timestamp:
            continue
        diff = abs(run_timestamp - sidecar["updated_ts"])
        if diff > 6 * 60 * 60:
            continue
        if best_diff is None or diff < best_diff:
            best, best_diff = sidecar, diff
    return best, best_diff


def summarize(values):
    if not values:
        return "n=0"
    ordered = sorted(values)
    p90 = ordered[min(len(ordered) - 1, int(len(ordered) * 0.9))]
    return "n=%d mean=%.1f median=%.1f p90=%d max=%d" % (
        len(values),
        statistics.mean(values),
        statistics.median(values),
        p90,
        max(values),
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--runs", default=DEFAULT_RUNS)
    parser.add_argument("--csv", default="")
    args = parser.parse_args()

    if not os.path.isdir(args.runs):
        print("runs directory not found:", args.runs)
        return 2

    rows = []
    act_ranges = defaultdict(lambda: [10 ** 9, -1])
    act_boss_floors = {1: Counter(), 2: Counter(), 3: Counter()}
    boss_errors = []
    changed_floors = Counter()

    for path in glob.glob(os.path.join(args.runs, "*", "*.run")):
        j = read_json(path)
        if not j:
            continue
        run_dir = os.path.basename(os.path.dirname(path))
        act1, act2, act3 = boss_floors(j)
        if act1:
            act_boss_floors[1][act1] += 1
        if act2:
            act_boss_floors[2][act2] += 1
        if act3:
            act_boss_floors[3][act3] += 1

        for battle in j.get("damage_taken") or []:
            floor = int(battle["floor"])
            act = act_of(floor, act1, act2, act3)
            act_ranges[act][0] = min(act_ranges[act][0], floor)
            act_ranges[act][1] = max(act_ranges[act][1], floor)
            old_act = 1 if floor <= 17 else 2 if floor <= 34 else 3 if floor <= 52 else 4
            if old_act != act:
                changed_floors[(battle.get("enemies"), old_act, act)] += 1
            name = battle.get("enemies")
            expected = 1 if name in ACT1_BOSSES else 2 if name in ACT2_BOSSES else 3 if name in ACT3_BOSSES else None
            if expected and expected != act:
                boss_errors.append((os.path.basename(path), name, floor, act1, act2, act3, act))

        exact = exact_gold(j)
        estimate = estimate_gold(j)
        sidecar_dir = os.path.join(args.runs, run_dir)
        sidecars = [
            state
            for state in (
                read_tinycat(p) for p in glob.glob(os.path.join(sidecar_dir, "*.tinycat"))
            )
            if state is not None
        ]
        tracked, tracked_diff = match_tinycat(int(j.get("timestamp", 0)), sidecars)

        rows.append(
            {
                "run": os.path.basename(path),
                "run_dir": run_dir,
                "character": j.get("character_chosen", "?"),
                "neow_bonus": j.get("neow_bonus") or "",
                "gold_exact": exact if exact is not None else "",
                "gold_estimate": estimate,
                "estimate_abs_error": abs(estimate - exact) if exact is not None else "",
                "tracked_gold": tracked["gold_gained"] if tracked else "",
                "tracked_floor0_hp": tracked["floor0_hp"] if tracked else "",
                "matched_seconds": tracked_diff if tracked else "",
            }
        )

    print("runs:", len(rows))
    exact_rows = [row for row in rows if row["gold_exact"] != ""]
    print("runs with exact gold in score_breakdown:", len(exact_rows))
    if exact_rows:
        errors = [row["estimate_abs_error"] for row in exact_rows]
        signed = [row["gold_estimate"] - row["gold_exact"] for row in exact_rows]
        print("estimator abs error:", summarize(errors))
        print(
            "estimator signed error (estimate-exact): mean=%.1f median=%.1f min=%d max=%d"
            % (statistics.mean(signed), statistics.median(signed), min(signed), max(signed))
        )
        by_bonus = defaultdict(list)
        for row in exact_rows:
            by_bonus[row["neow_bonus"]].append(row["estimate_abs_error"])
        print("estimator abs error by neow bonus:")
        for bonus, values in sorted(by_bonus.items(), key=lambda item: -len(item[1])):
            print("  %-24s %s" % (bonus, summarize(values)))

    tracked_rows = [row for row in rows if row["tracked_gold"] != ""]
    print("\nruns matched to .tinycat:", len(tracked_rows))
    if tracked_rows:
        diffs = [
            row["tracked_gold"] - row["gold_exact"]
            for row in tracked_rows
            if row["gold_exact"] != ""
        ]
        if diffs:
            print("tracked gold vs exact:", summarize([abs(value) for value in diffs]))
        floor0 = [row["tracked_floor0_hp"] for row in tracked_rows if row["tracked_floor0_hp"] > 0]
        print("tracked floor0_hp:", summarize(floor0))

    print("\nact mapping check:")
    for act in sorted(act_boss_floors):
        print("  act %d boss floors: %s" % (act, dict(sorted(act_boss_floors[act].items()))))
    print("  encounter floor ranges:", {act: tuple(act_ranges[act]) for act in sorted(act_ranges)})
    print("  boss assigned to wrong act:", len(boss_errors))
    for error in boss_errors[:10]:
        print("   ", error)
    print("  floors reclassified vs old 17/34/52 thresholds:", sum(changed_floors.values()))
    for key, count in changed_floors.most_common(10):
        print("   ", key, count)

    if args.csv:
        with open(args.csv, "w", newline="", encoding="utf-8-sig") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
            writer.writeheader()
            writer.writerows(rows)
        print("csv:", args.csv)
    return 0


if __name__ == "__main__":
    sys.exit(main())
