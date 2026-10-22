#!/usr/bin/env python3
"""
用真实 .run 数据核对 TinyCat 的累计金币估算公式。

对应 Java 代码：src/main/java/tinycat/stats/Run.java
- parseGold()
- getNeowBonusDeduction()
- readGoldTrackerOutput()

说明：
- .run 里的 gold 是“最终剩余金币”，不是累计获得金币。
- 本脚本比较“估算累计金币”和“最终金币”，用于观察:
  1) 估算是否低于最终金币（不可能）；
  2) 无消费局的偏差（主要来自 Neow 起始金币估算）；
  3) 有 gold-track 输出时的精确误差。

用法:
  python scripts/validate_gold_estimate.py
  python scripts/validate_gold_estimate.py --csv gold_report.csv
"""
import argparse
import csv
import json
import os
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


def read_json(path):
    for enc in ("utf-8", "gbk", "utf-8-sig"):
        try:
            with open(path, encoding=enc) as f:
                return json.load(f)
        except Exception:
            continue
    return None


def gold_tracker_output(runs_root, run_dir, timestamp):
    path = os.path.join(runs_root, "gold_track", run_dir, str(timestamp) + ".txt")
    if not os.path.isfile(path):
        return None
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            for line in f:
                line = line.strip()
                if line.startswith("gold_own:"):
                    return int(line.split(":", 1)[1].strip())
    except Exception:
        return None
    return None


def estimate_gold(j, runs_root, run_dir):
    """严格复刻 Run.parseGold 的 fallback 公式；gold-track 有值则返回精确值。"""
    timestamp = j.get("timestamp")
    exact = gold_tracker_output(runs_root, run_dir, timestamp) if timestamp else None
    if exact is not None and exact > 0:
        return exact, True

    gp = j.get("gold_per_floor") or []
    if not gp:
        return 0, False

    if j.get("neow_cost") == "NO_GOLD":
        start = 0
    else:
        start = DEDUCTION.get(j.get("neow_bonus"), 45)

    total = max(0, gp[0] - start)
    for i in range(1, len(gp)):
        diff = gp[i] - gp[i - 1]
        if diff > 0:
            total += diff
    if "MawBank" in (j.get("relics") or []):
        total += 12
    return total, False


def has_spending(j):
    if j.get("items_purchased"):
        return True
    if j.get("items_purged"):
        return True
    if j.get("purchased_purges", 0):
        return True
    for e in j.get("event_choices") or []:
        if e.get("gold_loss", 0) or e.get("gold_gain", 0):
            return True
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", default=DEFAULT_RUNS)
    ap.add_argument("--csv", default="")
    args = ap.parse_args()

    if not os.path.isdir(args.runs):
        print("runs directory not found:", args.runs)
        return 2

    rows = []
    for run_dir in sorted(os.listdir(args.runs)):
        d = os.path.join(args.runs, run_dir)
        if not os.path.isdir(d) or run_dir == "gold_track":
            continue
        for name in sorted(os.listdir(d)):
            if not name.endswith(".run"):
                continue
            j = read_json(os.path.join(d, name))
            if not j:
                continue
            estimate, exact = estimate_gold(j, args.runs, run_dir)
            final_gold = int(j.get("gold", 0))
            char = j.get("character_chosen", "?")
            rows.append({
                "run": name,
                "run_dir": run_dir,
                "character": char,
                "estimate": estimate,
                "final": final_gold,
                "diff": estimate - final_gold,
                "exact_source": exact,
                "spending": has_spending(j),
                "neow_bonus": j.get("neow_bonus") or "",
                "neow_cost": j.get("neow_cost") or "",
            })

    print("runs:", len(rows))
    print("gold-track exact:", sum(1 for r in rows if r["exact_source"]))
    print("estimate < final:", sum(1 for r in rows if r["diff"] < 0))
    print("estimate == final:", sum(1 for r in rows if r["diff"] == 0))
    print("estimate > final:", sum(1 for r in rows if r["diff"] > 0))
    diffs = [r["diff"] for r in rows]
    print("diff estimate-final: mean=%.2f median=%.2f min=%d max=%d" % (
        statistics.mean(diffs), statistics.median(diffs), min(diffs), max(diffs)))

    no_spend = [r for r in rows if not r["spending"]]
    print("\nno-spend runs:", len(no_spend))
    if no_spend:
        nd = [r["diff"] for r in no_spend]
        print("  diff: mean=%.2f median=%.2f min=%d max=%d" % (
            statistics.mean(nd), statistics.median(nd), min(nd), max(nd)))
        under = [r for r in no_spend if r["diff"] < 0]
        print("  estimate < final:", len(under), "(%.1f%%)" % (100.0 * len(under) / len(no_spend)))
        for r in sorted(no_spend, key=lambda x: x["diff"])[:10]:
            print("   ", r["character"], r["neow_bonus"], r["neow_cost"], "est", r["estimate"], "final", r["final"], "diff", r["diff"])

    by_char = defaultdict(list)
    for r in rows:
        by_char[r["character"]].append(r["diff"])
    print("\nby character:")
    for char, ds in sorted(by_char.items()):
        print(" ", char, "n=", len(ds), "mean=%.2f median=%.2f" % (statistics.mean(ds), statistics.median(ds)))

    # 第 1 层战损：TinyCat 当前口径是 damage - (Ironclad ? 6 : 0)
    print("\nfloor-1 damage estimate:")
    by_char_floor = defaultdict(list)
    for run_dir in sorted(os.listdir(args.runs)):
        d = os.path.join(args.runs, run_dir)
        if not os.path.isdir(d) or run_dir == "gold_track":
            continue
        for name in sorted(os.listdir(d)):
            if not name.endswith(".run"):
                continue
            j = read_json(os.path.join(d, name))
            if not j:
                continue
            f1 = [b for b in (j.get("damage_taken") or []) if int(b.get("floor", 0)) == 1]
            if not f1:
                continue
            damage = int(f1[0].get("damage", 0))
            loss = damage - (6 if j.get("character_chosen") == "IRONCLAD" else 0)
            by_char_floor[j.get("character_chosen", "?")].append(loss)
    for char, vals in sorted(by_char_floor.items()):
        print(" ", char, "n=", len(vals), "mean=%.2f median=%.2f" % (statistics.mean(vals), statistics.median(vals)))

    if args.csv:
        with open(args.csv, "w", newline="", encoding="utf-8-sig") as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
            w.writeheader()
            w.writerows(rows)
        print("csv:", args.csv)
    return 0


if __name__ == "__main__":
    sys.exit(main())
