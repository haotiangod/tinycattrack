# -*- coding: utf-8 -*-
"""卡牌标准化率校验：SKIP / 颂钵 该不该进配对矩阵。

口径：
  A 简单率            = 被选 / 被提供
  B BT 含 SKIP（颂钵并入 SKIP）  ← 与 mod 表格的合并方式一致
  C BT 含 SKIP + 颂钵（各自独立节点）
  D BT 不含 SKIP / 颂钵          ← 上一轮的做法

过滤尽量复刻 Run.valid()：A20、非每日/特殊/无尽、无 banned 卡/遗物/尼奥、
卡名不含 ':'。遭遇类 banned 名单未复刻（它只整局剔除，见输出里的局数对比）。

另附「复数抓位」检验：同一张卡第 1/2/3+ 次被提供时，抓取率是否系统性下降。
"""
from __future__ import annotations

import collections
import glob
import json
import math
import os
import statistics
import sys

RUNS_ROOT = r"C:/Program Files (x86)/Steam/steamapps/common/SlayTheSpire/runs"
# 存档位 1：RunHistoryManager 只加载名字里含 "1_" 的目录
SLOT_DIRS = ["1_IRONCLAD", "1_THE_SILENT", "1_DEFECT", "1_WATCHER"]
MIN_RUNS = 50
MIN_OFFERED = 20

BANNED_CARDS = {"pride", "omega"}
BANNED_RELICS = {"bargainbundle", "pet ghost", "replayfunnel"}
BANNED_NEOWS = {
    "CHOOSE_OTHER_CHAR_RANDOM_RARE_CARD", "SWAP_MEMBERSHIP_COURIER",
    "CHOOSE_OTHER_CHAR_RANDOM_UNCOMMON_CARD", "CHOOSE_OTHER_CHAR_RANDOM_COMMON_CARD",
    "GAIN_POTION_SLOT", "GAIN_TWO_POTION_SLOTS", "GAIN_TWO_RANDOM_COMMON_RELICS",
    "GAIN_UNCOMMON_RELIC",
}
CARD_TYPES_IGNORED = {"CURSE", "STATUS"}
SPECIAL = ("SKIP", "Singing Bowl")


def load(path):
    raw = open(path, "rb").read()
    for enc in ("utf-8", "gbk", "utf-8-sig"):
        try:
            return json.loads(raw.decode(enc))
        except Exception:
            continue
    return json.loads(raw.decode("utf-8", errors="ignore"))


def strip_upgrade(name):
    name = (name or "").strip()
    while len(name) > 2 and name[-2] == "+" and name[-1].isdigit():
        name = name[:-2]
    if name.endswith("+"):
        name = name[:-1]
    return name


def accepted(run):
    if run.get("is_daily") or run.get("is_special_run") or run.get("is_endless"):
        return False
    if not run.get("is_ascension_mode") or run.get("ascension_level") != 20:
        return False
    neow = run.get("neow_bonus")
    if not neow:
        return False
    if neow in BANNED_NEOWS:
        return False
    if any(n in BANNED_NEOWS for n in (run.get("neow_bonuses_skipped_log") or [])):
        return False
    deck = run.get("master_deck") or []
    for c in deck:
        low = strip_upgrade(c).lower()
        if ":" in c or low in BANNED_CARDS:
            return False
    for r in run.get("relics") or []:
        if ":" in r or r.lower() in BANNED_RELICS:
            return False
    for b in run.get("boss_relics") or []:
        for key in ("picked",):
            if b.get(key) and ":" in b[key]:
                return False
    return True


def events(run):
    """[(picked, [alts...], floor, {选项: 本局第几次出现})]，alts 按 mod 语义补 SKIP / 颂钵。"""
    bowl_floor = None
    for r in run.get("relics_obtained") or []:
        if r.get("key") == "Singing Bowl":
            f = r.get("floor")
            if f is not None and (bowl_floor is None or f < bowl_floor):
                bowl_floor = f
    out = []
    seen = collections.Counter()
    for c in run.get("card_choices") or []:
        picked = strip_upgrade(c.get("picked"))
        if not picked:
            continue
        floor = c.get("floor") or 0
        alts = [strip_upgrade(x) for x in (c.get("not_picked") or [])]
        alts = [a for a in alts if a]
        if picked not in SPECIAL:
            if bowl_floor is not None and bowl_floor <= floor:
                alts.append("Singing Bowl")
            else:
                alts.append("SKIP")
        depths = {}
        for name in [picked] + alts:
            seen[name] += 1
            depths[name] = seen[name]
        out.append((picked, alts, floor, depths))
    return out


def tally(evts):
    wins = collections.Counter()
    offered = collections.Counter()
    beats = collections.defaultdict(collections.Counter)
    for picked, alts, _, _ in evts:
        wins[picked] += 1
        offered[picked] += 1
        for a in alts:
            if a == picked:
                continue
            offered[a] += 1
            beats[picked][a] += 1
    return wins, offered, beats


def fit_bt(nodes, beats, prior=1.0, iters=200):
    """与 NeowRanker.fit 同款：MM 迭代 + 几何均值归一，prior 加在分子分母。"""
    nodes = list(nodes)
    s = {n: 1.0 for n in nodes}
    n_ij = {n: collections.Counter() for n in nodes}
    w = {n: 0.0 for n in nodes}
    for a, row in beats.items():
        if a not in s:
            continue
        for b, k in row.items():
            if b not in s:
                continue
            w[a] += k
            n_ij[a][b] += k
            n_ij[b][a] += k
    for _ in range(iters):
        nxt = {}
        for i in nodes:
            denom = 0.0
            for j, k in n_ij[i].items():
                denom += k / (s[i] + s[j])
            nxt[i] = (w[i] + prior) / (denom + prior) if denom > 0 else 1e-9
        logs = sum(math.log(max(v, 1e-12)) for v in nxt.values()) / len(nxt)
        g = math.exp(logs)
        s = {k: max(v / g, 1e-12) for k, v in nxt.items()}
    tot = sum(s.values())
    return {k: 100.0 * v / tot for k, v in s.items()}


def ranks(scores):
    order = sorted(scores.items(), key=lambda kv: -kv[1])
    return {n: i + 1 for i, (n, _) in enumerate(order)}


def overlap(a, b, k):
    ak = [n for n, _ in sorted(a.items(), key=lambda kv: -kv[1])[:k]]
    bk = [n for n, _ in sorted(b.items(), key=lambda kv: -kv[1])[:k]]
    return len(set(ak) & set(bk))


def top(scores, k):
    return [n for n, _ in sorted(scores.items(), key=lambda kv: -kv[1])[:k]]


def analyze(char, evts, act_filter):
    evts = [e for e in evts if act_filter(e[2])]
    wins, offered, beats = tally(evts)
    nodes = [n for n in offered if offered[n] >= MIN_OFFERED]
    cards = [n for n in nodes if n not in SPECIAL]

    simple = {n: 100.0 * wins[n] / offered[n] for n in nodes}
    merged_beats = collections.defaultdict(collections.Counter)
    for a, row in beats.items():
        ka = "SKIP" if a == "Singing Bowl" else a
        for b, k in row.items():
            kb = "SKIP" if b == "Singing Bowl" else b
            if ka == kb:
                continue
            merged_beats[ka][kb] += k
    merged_nodes = [n for n in nodes if n != "Singing Bowl"]
    bt_merged = fit_bt(merged_nodes, merged_beats)         # B：颂钵并入 SKIP
    bt_split = fit_bt(nodes, beats)                        # C：各自独立
    bt_noskip = fit_bt(cards, beats)                       # D：都不进

    s_card = {n: simple[n] for n in cards}
    b_card = {n: bt_merged.get(n, 0) for n in cards}
    c_card = {n: bt_split.get(n, 0) for n in cards}
    d_card = {n: bt_noskip.get(n, 0) for n in cards}
    rc, rb, rsp, rn = ranks(s_card), ranks(b_card), ranks(c_card), ranks(d_card)

    def delta(p, q):
        return [abs(p[n] - q[n]) for n in cards]

    d_b, d_c, d_d = delta(rc, rb), delta(rc, rsp), delta(rc, rn)
    d_bc, d_bd = delta(rb, rsp), delta(rb, rn)

    biggest = sorted(cards, key=lambda n: -abs(rb[n] - rn[n]))[:6]
    zero = [n for n in cards if wins[n] == 0]

    return {
        "char": char,
        "events": len(evts),
        "cards": len(cards),
        "skip_offer": offered.get("SKIP", 0),
        "skip_pick": wins.get("SKIP", 0),
        "skip_simple": simple.get("SKIP", float("nan")),
        "skip_bt": bt_merged.get("SKIP", float("nan")),
        "bowl_offer": offered.get("Singing Bowl", 0),
        "bowl_simple": simple.get("Singing Bowl", float("nan")),
        "bowl_bt": bt_split.get("Singing Bowl", float("nan")),
        "d_b": statistics.mean(d_b), "max_b": max(d_b),
        "d_c": statistics.mean(d_c), "max_c": max(d_c),
        "d_d": statistics.mean(d_d), "max_d": max(d_d),
        "d_bc": statistics.mean(d_bc), "max_bc": max(d_bc),
        "d_bd": statistics.mean(d_bd), "max_bd": max(d_bd),
        "top10_b": overlap(b_card, s_card, 10),
        "top20_b": overlap(b_card, s_card, 20),
        "top10_c": overlap(c_card, s_card, 10),
        "top10_d": overlap(d_card, s_card, 10),
        "top10_bc": overlap(b_card, c_card, 10),
        "top10_bd": overlap(b_card, d_card, 10),
        "zero": len(zero),
        "zero_rank_spread": (min(rb[n] for n in zero), max(rb[n] for n in zero)) if zero else None,
        "biggest": [(n, simple[n], rb[n], rc[n], rn[n]) for n in biggest],
        "scores": {"simple": simple, "merged": bt_merged, "split": bt_split, "noskip": bt_noskip},
        "ranks": {"simple": rc, "merged": rb, "split": rsp, "noskip": rn},
    }


def copy_depth(evts):
    """同一张卡在本局第 k 次被提供时，抓取率是否下降（用 within-card 差，避免卡池构成偏差）。"""
    by_card = collections.defaultdict(lambda: collections.defaultdict(lambda: [0, 0]))
    for picked, alts, _, depths in evts:
        for name in [picked] + list(alts):
            if name in SPECIAL:
                continue
            stratum = min(depths.get(name, 1), 3)
            by_card[name][stratum][1] += 1
            if name == picked:
                by_card[name][stratum][0] += 1
    rows = []
    for name, strata in by_card.items():
        if 1 not in strata or 2 not in strata:
            continue
        p1 = strata[1][0] / strata[1][1]
        p2 = strata[2][0] / strata[2][1]
        n = min(strata[1][1], strata[2][1])
        if strata[1][1] < 30 or strata[2][1] < 15:
            continue
        rows.append((name, p1, p2, strata[1][1], strata[2][1], n))
    return rows


def main():
    by_char = collections.defaultdict(list)
    for name in SLOT_DIRS:
        d = os.path.join(RUNS_ROOT, name)
        for path in glob.glob(os.path.join(d, "*.run")):
            try:
                run = load(path)
            except Exception:
                continue
            if not accepted(run):
                continue
            by_char[run.get("character_chosen")].append(run)

    print("载入局数：")
    for c, rs in by_char.items():
        print("  %-12s %d" % (c, len(rs)))

    for label, act_filter in (("第一幕 (floor<17)", lambda f: f < 17),
                              ("第一幕后 (floor>=17)", lambda f: f >= 17)):
        print("\n" + "=" * 78)
        print("### %s" % label)
        rows = []
        for char, runs in sorted(by_char.items()):
            if len(runs) < MIN_RUNS:
                continue
            evts = []
            for r in runs:
                evts.extend(events(r))
            rows.append(analyze(char, evts, act_filter))

        print("%-12s %6s %5s %9s %9s %9s %9s" %
              ("角色", "奖励", "卡种", "ΔB-简单", "ΔD-简单", "ΔB-D", "前10B/前10D"))
        for r in rows:
            print("%-12s %6d %5d %9.2f %9.2f %9.2f %6d/%d" %
                  (r["char"], r["events"], r["cards"], r["d_b"], r["d_d"], r["d_bd"],
                   r["top10_b"], r["top10_d"]))
        print()
        print("%-12s %10s %8s %10s %8s %10s %8s %8s" %
              ("角色", "SKIP提供", "跳过率", "SKIP强度", "颂钵率", "颂钵强度", "最大ΔB-D", "0选卡"))
        for r in rows:
            print("%-12s %10d %7.1f%% %10.2f %7.1f%% %10.2f %8d %8d" %
                  (r["char"], r["skip_offer"], r["skip_simple"], r["skip_bt"],
                   r["bowl_simple"], r["bowl_bt"], int(r["max_bd"]), r["zero"]))
        print()
        print("含SKIP 与否 名次差最大的卡（B 含SKIP vs D 不含）：")
        for r in rows:
            print("  %s   BT名次 简单名次 不含SKIP名次" % r["char"])
            for name, s, b, rc, rn in r["biggest"]:
                print("    %-24s 简单 %6.2f%%   BT %3d   简单 %3d   不含 %3d" % (name, s, b, rc, rn))
        print()
        print("前 10 交集：BT含SKIP vs BT不含SKIP = %s；BT含SKIP vs 简单率 = %s；颂钵拆分 vs 合并 = %s" %
              (", ".join("%s %d/10" % (r["char"], r["top10_bd"]) for r in rows),
               ", ".join("%s %d/10" % (r["char"], r["top10_b"]) for r in rows),
               ", ".join("%s %d/10" % (r["char"], r["top10_bc"]) for r in rows)))
        print("前 20 交集：BT含SKIP vs 简单率 = %s" %
              ", ".join("%s %d/20" % (r["char"], r["top20_b"]) for r in rows))

    print("\n" + "=" * 78)
    print("### 复数抓位：同一张卡第 1 次 vs 第 2 次被提供时的抓取率")
    for char, runs in sorted(by_char.items()):
        if len(runs) < MIN_RUNS:
            continue
        evts = []
        for r in runs:
            evts.extend(events(r))
        rows = copy_depth(evts)
        if not rows:
            continue
        drop = [r[2] - r[1] for r in rows]
        weighted = sum(r[2] - r[1] for r in rows) / len(rows)
        down = sum(1 for d in drop if d < 0)
        print("%-12s 卡种 %3d  平均差 %+6.2fpp  中位 %+6.2fpp  下降占比 %d/%d" %
              (char, len(rows), 100 * weighted, 100 * statistics.median(drop), down, len(rows)))
        for name, p1, p2, n1, n2, _ in sorted(rows, key=lambda x: x[2] - x[1])[:5]:
            print("    %-24s %6.1f%% (n=%4d) → %6.1f%% (n=%3d)" % (name, 100 * p1, n1, 100 * p2, n2))


if __name__ == "__main__":
    main()
