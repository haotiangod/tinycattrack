# -*- coding: utf-8 -*-
"""
卡牌标准化率校验：
  口径 A（简单率）  = 被选 / 被提供
  口径 B（BT 含 SKIP/颂钵）= 配对矩阵 + Bradley-Terry
  口径 C（BT 不含 SKIP/颂钵）

SKIP 与颂钵的“被提供”必须按 mod 的真实语义补：
  Run.java 里，只要 picked 不是 SKIP/颂钵，就往 not_picked 补一个 SKIP
  （拥有颂钵后改为补 Singing Bowl）。所以 SKIP 在**每一次**卡牌奖励里都参与竞争。
"""
import json
import os
import glob
import math
import collections

RUNS_DIR = r"C:\Users\MI\Downloads\runs"
MIN_RUNS = 50


def strip_upgrade(name):
    name = name.strip()
    while len(name) > 2 and name[-2] == "+" and name[-1].isdigit():
        name = name[:-2]
    if name.endswith("+"):
        name = name[:-1]
    return name


def load_runs():
    by_char = collections.defaultdict(list)
    for path in glob.glob(os.path.join(RUNS_DIR, "**", "*.run"), recursive=True):
        try:
            with open(path, "r", encoding="utf-8", errors="ignore") as f:
                data = json.load(f)
        except Exception:
            continue
        char = data.get("character_chosen")
        if not char:
            continue
        by_char[char].append(data)
    return by_char


def build_events(data):
    """返回 [(picked, [其它选项...]), ...]，其它选项已按 mod 语义补齐 SKIP/颂钵"""
    bowl_floor = 1 << 30
    for r in data.get("relics_obtained", []) or []:
        if r.get("key") == "Singing Bowl":
            bowl_floor = min(bowl_floor, r.get("floor", 1 << 30))
    out = []
    for c in data.get("card_choices", []) or []:
        picked = strip_upgrade(c.get("picked") or "")
        if not picked:
            continue
        notp = [strip_upgrade(x) for x in (c.get("not_picked") or [])]
        if picked not in ("SKIP", "Singing Bowl"):
            if bowl_floor <= c.get("floor", 1 << 30):
                notp.append("Singing Bowl")
            else:
                notp.append("SKIP")
        out.append((picked, notp))
    return out


def tally(events):
    wins = collections.Counter()
    offered = collections.Counter()
    beats = collections.defaultdict(lambda: collections.Counter())
    for picked, alts in events:
        offered[picked] += 1
        wins[picked] += 1
        for a in alts:
            if a == picked:
                continue
            offered[a] += 1
            beats[picked][a] += 1
    return wins, offered, beats


def fit_bt(nodes, beats, iters=500):
    """MM 迭代；返回 {node: strength}，几何均值归一到 1"""
    w = {n: 0.0 for n in nodes}
    n_ij = {n: collections.Counter() for n in nodes}
    for a, row in beats.items():
        if a not in w:
            continue
        for b, k in row.items():
            if b not in w:
                continue
            w[a] += k
            n_ij[a][b] += k
            n_ij[b][a] += k
    s = {n: 1.0 for n in nodes}
    for _ in range(iters):
        new = {}
        for i in nodes:
            denom = 0.0
            for j, k in n_ij[i].items():
                denom += k / (s[i] + s[j])
            new[i] = (w[i] + 0.5) / (denom + 0.5) if denom > 0 else s[i]
        # 几何均值归一
        logs = sum(math.log(max(v, 1e-12)) for v in new.values()) / len(new)
        g = math.exp(logs)
        s = {k: max(v / g, 1e-12) for k, v in new.items()}
    tot = sum(s.values())
    return {k: 100.0 * v / tot for k, v in s.items()}


def rank_of(scores, exclude):
    items = [(k, v) for k, v in scores.items() if k not in exclude]
    items.sort(key=lambda x: -x[1])
    return [k for k, _ in items]


def compare(char, runs):
    events = []
    for d in runs:
        events.extend(build_events(d))
    wins, offered, beats = tally(events)
    if not events:
        return None
    all_nodes = [n for n in offered if offered[n] >= 20]

    simple = {n: 100.0 * wins[n] / offered[n] for n in all_nodes}

    full = fit_bt(all_nodes, beats)
    no_skip = fit_bt([n for n in all_nodes if n not in ("SKIP", "Singing Bowl")], beats)

    special = {"SKIP", "Singing Bowl"}
    cards = [n for n in all_nodes if n not in special]

    r_simple = rank_of(simple, set())
    r_full = rank_of(full, set())
    r_noskip = rank_of({k: v for k, v in no_skip.items()}, set())

    def pos(seq):
        return {n: i + 1 for i, n in enumerate(seq)}

    ps, pf, pn = pos(r_simple), pos(r_full), pos(r_noskip)
    common = [n for n in cards if n in ps and n in pf and n in pn]

    d_full = [abs(pf[n] - ps[n]) for n in common]
    d_noskip = [abs(pn[n] - ps[n]) for n in common]
    d_pair = [abs(pf[n] - pn[n]) for n in common]

    def overlap(pa, pb, k):
        return len(set(rank_of(pa, set())[:k]) & set(rank_of(pb, set())[:k]))

    top = list(sorted(cards, key=lambda n: -full.get(n, 0)))[:10]
    return {
        "char": char,
        "runs": len(runs),
        "events": len(events),
        "cards": len(common),
        "skip_rate": 100.0 * wins["SKIP"] / offered["SKIP"] if offered["SKIP"] else 0,
        "bowl_rate": 100.0 * wins["Singing Bowl"] / offered["Singing Bowl"] if offered["Singing Bowl"] else 0,
        "skip_bt": full.get("SKIP", 0),
        "bowl_bt": full.get("Singing Bowl", 0),
        "max_card_bt": max(full[n] for n in cards),
        "d_full": sum(d_full) / len(d_full),
        "d_noskip": sum(d_noskip) / len(d_noskip),
        "d_pair": sum(d_pair) / len(d_pair),
        "max_full": max(d_full),
        "max_noskip": max(d_noskip),
        "top10_full": overlap(full, simple, 10),
        "top10_noskip": overlap(no_skip, simple, 10),
        "top10_pair": overlap(full, no_skip, 10),
        "top20_full": overlap(full, simple, 20),
        "top20_pair": overlap(full, no_skip, 20),
        "top": [(n, simple[n], full.get(n, 0), no_skip.get(n, 0)) for n in top],
        "full": full,
        "no_skip": no_skip,
        "simple": simple,
        "rank_full": pf,
        "rank_noskip": pn,
        "rank_simple": ps,
    }


def main():
    by_char = load_runs()
    rows = []
    for char, runs in sorted(by_char.items()):
        if len(runs) < MIN_RUNS:
            print("skip %s (n=%d)" % (char, len(runs)))
            continue
        r = compare(char, runs)
        if r:
            rows.append(r)

    print()
    print("%-12s %5s %7s %5s %8s %8s %7s %7s" %
          ("角色", "局数", "奖励", "卡种", "跳过率", "颂钵率", "SKIP分", "颂钵分"))
    for r in rows:
        print("%-12s %5d %7d %5d %7.1f%% %7.1f%% %7.2f %7.2f" %
              (r["char"], r["runs"], r["events"], r["cards"],
               r["skip_rate"], r["bowl_rate"], r["skip_bt"], r["bowl_bt"]))

    print()
    print("名次变动（对比简单率）；pair = 含SKIP vs 不含SKIP 的标准化率之间")
    print("%-12s %8s %8s %8s %8s %10s %10s %8s %8s" %
          ("角色", "Δ含SKIP", "Δ不含", "Δ对含VS不", "Δ最大", "前10含", "前10不含", "前20含", "前10对含不"))
    for r in rows:
        print("%-12s %8.2f %8.2f %8.2f %8d %9d/10 %9d/10 %7d/20 %8d/10" %
              (r["char"], r["d_full"], r["d_noskip"], r["d_pair"], int(r["max_full"]),
               r["top10_full"], r["top10_noskip"], r["top20_full"], r["top10_pair"]))

    print()
    for r in rows:
        print("=== %s (n=%d)  Top10 ===" % (r["char"], r["runs"]))
        print("  %-22s %8s %8s %8s %6s %6s" % ("卡牌", "简单率", "含SKIP", "不含", "次含", "次不含"))
        for name, s, f, n in r["top"]:
            print("  %-22s %7.2f%% %7.2f%% %7.2f%% %6d %6d" %
                  (name, s, f, n, r["rank_full"][name], r["rank_noskip"][name]))


if __name__ == "__main__":
    main()
