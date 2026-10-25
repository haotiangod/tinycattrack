# -*- coding: utf-8 -*-
"""独立校验 TinyCat 导出包：用 runs/*.run 原始数据复算，再和导出的 xlsx / csv 比对。

用法：
    python verify_export.py <导出目录> [--runs 游戏runs目录] [--class DEFECT]

复刻口径见 docs/CORE-LOGIC.md：
- 过滤：A20、非每日/特殊/无尽、卡牌与遗物名不含 ':'、不含 banned 卡/遗物、日期范围、放弃局、种子局
- 胜：isHeartKill（victory 且 damage_taken 出现过 The Heart）
- 战损：actualHPLoss = 前一层结束HP - 本层结束HP（第 1 层按 floor0HP 估算：机器人/猎人/观者 = 本层HP+本层伤害）
- 平均：Mean = Σ/n，每次遭遇一个样本；每幕总战损按「每局一个样本」
- 梯度：Boss 全进 T0 按均值降序；非 Boss 按幕内等人数切 T1..T4；不设样本下限（lowSample 只标注）
"""
from __future__ import annotations

import csv
import glob
import io
import json
import os
import re
import statistics
import sys
import zipfile
from collections import defaultdict

GAME = r"C:/Program Files (x86)/Steam/steamapps/common/SlayTheSpire"
DEFAULT_RUNS = os.path.join(GAME, "runs")

BANNED_CARDS = {"pride", "omega"}
CARD_RENAMES = {}
BANNED_RELICS = {"bargainbundle", "pet ghost", "replayfunnel"}

BOSS_ACT1 = ("Hexaghost", "Slime Boss", "The Guardian")
BOSS_ACT2 = ("Collector", "Automaton", "Champ")
BOSS_ACT3 = ("Time Eater", "Awakened One", "Donu and Deca")
BOSS_NAMES = set(BOSS_ACT1) | set(BOSS_ACT2) | set(BOSS_ACT3)

def norm_name(x):
    base = re.sub(r"\+\d+$", "", (x or "").strip())
    return re.sub(r"[^a-z0-9]", "", base.lower())


def load_card_renames():
    """从项目源码里读 CardNameNormalizer 的重命名表（Conserve Battery → Charge Battery 等）。"""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        "..", "Desktop", "project", "tinycat", "src", "main", "java",
                        "tinycat", "CardNameNormalizer.java")
    path = r"C:/Users/MI/Desktop/project/tinycat/src/main/java/tinycat/CardNameNormalizer.java"
    out = {}
    try:
        src = io.open(path, encoding="utf-8").read()
        for a, b in re.findall(r'map\.put\("([^"]+)",\s*"([^"]+)"\)', src):
            out[norm_name(a)] = norm_name(b)
    except Exception:
        pass
    return out


NAME_FIX = {
    "BookOfStabbing": "Book of Stabbing",
    "HealerTank": "Centurion and Healer",
    "3_Byrds": "3 Byrds",
    "GiantHead": "Giant Head",
    "Darkling Encounter": "3 Darklings",
    "Gremlin Leader Combat": "Gremlin Leader",
    "SlimeBoss": "Slime Boss",
    "Double Orb Walker": "2 Orb Walkers",
    "JawWorm": "Jaw Worm",
    "Shelled Parasite": "Shell Parasite",
    "GremlinNob": "Gremlin Nob",
    "Sentries": "3 Sentries",
}

NEOW_DEDUCTION = {
    "TWO_FIFTY_GOLD": 55, "HUNDRED_GOLD": 40, "RANDOM_COMMON_RELIC": 35,
    "REMOVE_CARD": 40, "REMOVE_TWO": 40, "ONE_RARE_RELIC": 45,
    "TRANSFORM_TWO_CARDS": 55, "TRANSFORM_CARD": 60, "UPGRADE_CARD": 60,
    "THREE_SMALL_POTIONS": 45, "RANDOM_COLORLESS_2": 60, "THREE_RARE_CARDS": 45,
    "THREE_ENEMY_KILL": 40,
}


# ---------------------------------------------------------------- 读取 .run
def load_json(path):
    for enc in ("utf-8", "gbk"):
        try:
            with io.open(path, encoding=enc) as fh:
                return json.load(fh)
        except Exception:
            continue
    return None


class Run:
    __slots__ = ("d", "path", "boss1", "boss2", "boss3", "heart")

    def __init__(self, path, d):
        self.path = path
        self.d = d
        dt = d.get("damage_taken") or []
        a1 = a2 = a3 = -1
        for b in dt:
            n = NAME_FIX.get(b.get("enemies"), b.get("enemies"))
            fl = int(b.get("floor") or 0)
            if n in BOSS_ACT1:
                a1 = fl if a1 < 0 else min(a1, fl)
            elif n in BOSS_ACT2:
                a2 = fl if a2 < 0 else min(a2, fl)
            elif n in BOSS_ACT3:
                a3 = max(a3, fl)
        self.boss1, self.boss2, self.boss3 = a1, a2, a3
        self.heart = bool(d.get("victory")) and any(
            (b.get("enemies") == "The Heart") for b in dt)

    # --- 幕 / 楼层
    def act(self, floor):
        if self.boss1 > 0:
            if floor <= self.boss1:
                return 1
            if self.boss2 <= 0:
                return 2
            if floor <= self.boss2:
                return 2
            if self.boss3 <= 0:
                return 3
            if floor <= self.boss3:
                return 3
            return 4
        if self.d.get("is_endless"):
            return 4
        if floor <= 17:
            return 1
        if floor <= 34:
            return 2
        if floor <= 52:
            return 3
        return 4

    def reward_floor(self, floor):
        if floor <= 0:
            return False
        for b in (self.boss1, self.boss2, self.boss3):
            if b > 0 and floor == b + 1:
                return True
        path = self.d.get("path_per_floor") or []
        return floor <= len(path) and path[floor - 1] == "T"

    def act_for_stats(self, floor):
        return 0 if self.reward_floor(floor) else self.act(floor)

    def last_act_for_stats(self):
        floor = max(1, int(self.d.get("floor_reached") or 1))
        while floor > 1 and self.reward_floor(floor):
            floor -= 1
        return min(4, max(1, self.act(floor)))

    def node(self, floor):
        path = self.d.get("path_per_floor") or []
        if floor <= 0 or floor > len(path):
            return ""
        return path[floor - 1] or ""

    # --- 战损
    def floor0_hp(self, cls):
        hp = self.d.get("current_hp_per_floor") or []
        if not hp:
            maxhp = self.d.get("max_hp_per_floor") or []
            return maxhp[0] if maxhp else 70
        damage1 = 0
        for b in self.d.get("damage_taken") or []:
            if int(b.get("floor") or 0) == 1:
                damage1 = int(b.get("damage") or 0)
                break
        if cls == "IRONCLAD":
            return hp[0] + damage1 - 6
        return hp[0] + damage1

    def encounters(self, cls):
        """返回 [(floor, name, damage, actualHPLoss, node)]"""
        hp = self.d.get("current_hp_per_floor") or []
        f0 = self.floor0_hp(cls)
        out = []
        for b in self.d.get("damage_taken") or []:
            floor = int(b.get("floor") or 0)
            name = NAME_FIX.get(b.get("enemies"), b.get("enemies"))
            damage = int(b.get("damage") or 0)
            if damage >= 99999:
                damage -= 99999
            if floor == 1:
                before, after = f0, (hp[0] if hp else 0)
            elif floor >= 2 and floor - 1 < len(hp):
                before, after = hp[floor - 2], hp[floor - 1]
            else:
                before = after = 0
            out.append((floor, name, damage, before - after, self.node(floor)))
        return out

    # --- 金币（vF 估算）
    def gold_estimate(self):
        g = self.d.get("gold_per_floor") or []
        if not g:
            return 0
        bonus = self.d.get("neow_bonus")
        cost = self.d.get("neow_cost")
        if cost == "NO_GOLD":
            ded = 0
        else:
            ded = NEOW_DEDUCTION.get(bonus, 45)
        total = max(0, g[0] - ded)
        for i in range(1, len(g)):
            diff = g[i] - g[i - 1]
            if diff > 0:
                total += diff
        if "MawBank" in (self.d.get("relics") or []):
            total += 12
        return total

    def kills(self):
        enemies = bosses = 0
        for s in self.d.get("score_breakdown") or []:
            m = re.match(r"(?:Enemies Slain|杀死的敌人) \((\d+)\):.*", s)
            if m:
                enemies = int(m.group(1))
            m = re.match(r"(?:Bosses Slain|打败Boss) \((\d+)\):.*", s)
            if m:
                bosses = int(m.group(1))
        return enemies, bosses


def valid(run: Run):
    d = run.d
    if d.get("is_special_run") or d.get("is_daily") or d.get("is_endless"):
        return False
    if d.get("ascension_level") != 20:
        return False
    for c in d.get("master_deck") or []:
        if ":" in c:
            return False
        base = re.sub(r"\+\d+$", "", c).lower()
        if base in BANNED_CARDS:
            return False
    for r in d.get("relics") or []:
        if ":" in r:
            return False
        if r.lower() in BANNED_RELICS:
            return False
    return True


def in_range(d, start, end):
    ts = int(d.get("timestamp") or 0)
    import datetime
    dt = datetime.datetime.fromtimestamp(ts)
    key = (dt.year, dt.month)
    return start <= key <= end


def load_class(cls, runs_root, start, end, abandons=True):
    folder = None
    for name in (f"1_{cls}", cls):
        p = os.path.join(runs_root, name)
        if os.path.isdir(p):
            folder = p
            break
    if folder is None:
        return []
    out = []
    for f in glob.glob(os.path.join(folder, "*.run")):
        d = load_json(f)
        if d is None:
            continue
        r = Run(f, d)
        if not valid(r) or not in_range(d, start, end):
            continue
        if not abandons and d.get("abandoned"):
            continue
        out.append(r)
    return out


# ---------------------------------------------------------------- 读导出
def xlsx_rows(path, sheet="xl/worksheets/sheet1.xml"):
    z = zipfile.ZipFile(path)
    x = z.read(sheet).decode("utf-8")
    rows = []
    for r in re.findall(r"<row[^>]*>(.*?)</row>", x, re.S):
        cells = []
        for m in re.findall(r"<c [^>]*>(.*?)</c>|<c [^>]*/>", r, re.S):
            t = re.findall(r"<t[^>]*>(.*?)</t>", m, re.S)
            v = re.findall(r"<v>(.*?)</v>", m, re.S)
            cells.append(t[0] if t else (v[0] if v else ""))
        rows.append(cells)
    return rows


def xlsx_sheets(path):
    z = zipfile.ZipFile(path)
    return sorted(n for n in z.namelist() if n.startswith("xl/worksheets/sheet"))


def num(x, default=float("nan")):
    try:
        return float(x)
    except (TypeError, ValueError):
        return default


# ---------------------------------------------------------------- 复算
def compute(runs, cls):
    if not CARD_RENAMES:
        CARD_RENAMES.update(load_card_renames())
    out = {}
    out["runs"] = len(runs)
    out["wins"] = sum(1 for r in runs if r.heart)
    out["floors"] = sum(int(r.d.get("floor_reached") or 0) for r in runs)
    out["score_max"] = max((int(r.d.get("score") or 0) for r in runs), default=0)
    out["score_mean"] = statistics.mean(
        [int(r.d.get("score") or 0) for r in runs]) if runs else 0
    out["gold_mean"] = statistics.mean([r.gold_estimate() for r in runs]) if runs else 0
    e = b = 0
    for r in runs:
        ee, bb = r.kills()
        e += ee
        b += bb
    out["enemies"] = e
    out["bosses"] = b

    # 遭遇（每次一个样本）
    dmg = defaultdict(list)
    loss = defaultdict(list)
    tier_loss = defaultdict(list)   # 只含 M/E/B（+ 无路径的 Boss），梯度表用
    per_act_sum = defaultdict(list)   # 每局每幕一个样本
    boss_tot = defaultdict(list)
    nonboss_tot = defaultdict(list)
    for r in runs:
        act_sum = defaultdict(int)
        act_boss = defaultdict(int)
        act_non = defaultdict(int)
        for floor, name, damage, hp_loss, node in r.encounters(cls):
            if name is None or str(name).startswith("事件:"):
                continue
            act = r.act_for_stats(floor)
            if act == 0:
                continue
            dmg[(act, name)].append(damage)
            loss[(act, name)].append(hp_loss)
            if node in ("M", "E", "B") or (node == "" and name in BOSS_NAMES):
                tier_loss[(act, name)].append(hp_loss)
            act_sum[act] += hp_loss
            if name in BOSS_NAMES:
                act_boss[act] += hp_loss
            else:
                act_non[act] += hp_loss
        last = r.last_act_for_stats()
        for act in range(1, last + 1):
            act_sum.setdefault(act, 0)
        for act, v in act_sum.items():
            per_act_sum[act].append(v)
        for act, v in act_boss.items():
            boss_tot[act].append(v)
        for act, v in act_non.items():
            nonboss_tot[act].append(v)
    out["enc_dmg"] = dmg
    out["enc_loss"] = loss
    out["all_loss"] = per_act_sum
    out["boss_total"] = boss_tot
    out["nonboss_total"] = nonboss_tot

    # 删牌
    shop = event = neow = camp = cage = 0
    for r in runs:
        d = r.d
        shop += len(d.get("items_purged") or [])
        for ev in d.get("event_choices") or []:
            event += len(ev.get("cards_removed") or [])
        log = d.get("neow_bonus_log") or {}
        neow += len(log.get("cardsRemoved") or log.get("cards_removed") or [])
        for cf in d.get("campfire_choices") or []:
            if cf.get("key") == "PURGE" and cf.get("data") is not None:
                camp += 1
        if "Empty Cage" in (d.get("relics") or []):
            cage += 2
    out["purge"] = dict(total=shop + event + neow + camp + cage, shop=shop,
                        event=event, neow=neow, campfire=camp, cage=cage)

    # 问号 / 事件
    q = 0
    ev = 0
    for r in runs:
        taken = r.d.get("path_taken") or []
        q += sum(1 for x in taken if x in ("?", "EVENT"))
        ev += len(r.d.get("event_choices") or [])
    out["question"] = q
    out["events"] = ev

    # 卡牌抓率（第一幕/<17 与 第一幕后）
    act1 = defaultdict(lambda: [0, 0])   # name -> [picked, offered]
    after = defaultdict(lambda: [0, 0])
    for r in runs:
        for c in r.d.get("card_choices") or []:
            floor = int(c.get("floor") or 0)
            picked = c.get("picked")
            skipped = c.get("not_picked") or []
            tgt = act1 if floor < 17 else after
            pkey = CARD_RENAMES.get(norm_name(picked), norm_name(picked))
            tgt[pkey][0] += 1
            tgt[pkey][1] += 1
            for s in skipped:
                skey = CARD_RENAMES.get(norm_name(s), norm_name(s))
                tgt[skey][1] += 1
    out["card_act1"] = act1
    out["card_after"] = after

    # 怪物梯度（每幕，Boss/非Boss，等人数分档；gate=None 表示不设下限，否则按门槛排除）
    def build_tiers(gate_boss, gate_nonboss):
        res = {}
        for act in (1, 2, 3):
            bosses, nonb, low = [], [], []
            for (a, name), vals in tier_loss.items():
                if a != act:
                    continue
                item = dict(name=name, n=len(vals), mean=sum(vals) / len(vals))
                gate = gate_boss if name in BOSS_NAMES else gate_nonboss
                if gate is not None and item["n"] < gate:
                    low.append(item)
                    continue
                (bosses if name in BOSS_NAMES else nonb).append(item)
            bosses.sort(key=lambda x: -x["mean"])
            nonb.sort(key=lambda x: -x["mean"])
            total = len(nonb)
            base = total // 4
            extra = total % 4
            idx = 0
            for ti in range(4):
                cnt = base + (1 if ti < extra else 0)
                for rank in range(1, cnt + 1):
                    nonb[idx]["tier"] = "T%d" % (ti + 1)
                    nonb[idx]["rank"] = rank
                    idx += 1
            for i, b in enumerate(bosses, 1):
                b["tier"] = "T0"
                b["rank"] = i
            res[act] = dict(bosses=bosses, nonboss=nonb, low=low)
        return res
    out["tiers_all"] = build_tiers(None, None)

    # 怪物梯度：按导出时的门槛（Boss>=5 / 非Boss>=10）复算一份，再给一份不设下限的
    out["tiers"] = build_tiers(5, 10)
    out["tiers_all"] = build_tiers(None, None)

    return out


# ---------------------------------------------------------------- 报告
def check(rows, label, expected, actual, tol=1e-6, note=""):
    ok = abs(expected - actual) <= tol if isinstance(expected, float) else expected == actual
    rows.append((label, expected, actual, "OK" if ok else "DIFF", note))
    return ok


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 2
    export_dir = args[0]
    runs_root = DEFAULT_RUNS
    cls = "DEFECT"
    abandons = True
    for i, a in enumerate(args):
        if a == "--runs" and i + 1 < len(args):
            runs_root = args[i + 1]
        if a == "--class" and i + 1 < len(args):
            cls = args[i + 1]

    data = json.load(io.open(os.path.join(export_dir, "data.json"), encoding="utf-8"))
    filters = data["filters"]
    start = tuple(int(x) for x in data["date_range"]["start"].split("-"))
    end = tuple(int(x) for x in data["date_range"]["end"].split("-"))
    abandons = bool(filters.get("include_abandons"))

    runs = load_class(cls, runs_root, start, end, abandons)
    got = compute(runs, cls)
    print(f"导出包 : {export_dir}")
    print(f"角色   : {cls}   日期 {start}~{end}   放弃局包含={abandons}")
    print(f"原始   : {len(runs)} 局（A20 / 非每日特殊 / 合法卡组遗物）\n")

    rows = []
    # --- 基础
    s1 = xlsx_rows(os.path.join(export_dir, "总体统计.xlsx"))
    char_rows = {}
    for r in s1:
        if len(r) > 3 and r[1] in ("ALL", "IRONCLAD", "THE_SILENT", "DEFECT", "WATCHER"):
            char_rows[r[1]] = r
    row = char_rows.get(cls)
    check(rows, "对局数", num(row[3]), got["runs"], 0)
    check(rows, "胜场", num(row[4]), got["wins"], 0)
    s2 = xlsx_rows(os.path.join(export_dir, "总体统计.xlsx"), "xl/worksheets/sheet2.xml")
    row2 = {}
    for r in s2:
        if len(r) > 3 and r[1] in ("ALL", "IRONCLAD", "THE_SILENT", "DEFECT", "WATCHER"):
            row2[r[1]] = r
    r2 = row2.get(cls)
    check(rows, "总层数", num(r2[2]), got["floors"], 0)
    check(rows, "Boss击杀", num(r2[3]), got["bosses"], 0)
    check(rows, "敌人击杀", num(r2[4]), got["enemies"], 0)
    check(rows, "最高分", num(r2[5]), got["score_max"], 0)
    check(rows, "平均分", num(r2[6]), round(got["score_mean"], 6), 1e-3)
    check(rows, "平均累计金币", num(r2[7]), round(got["gold_mean"], 6), 1e-3, "vF 估算")
    check(rows, "总问号节点", num(r2[10]) if len(r2) > 10 else -1, got["question"], 0)
    check(rows, "总事件", num(r2[11]) if len(r2) > 11 else -1, got["events"], 0)

    s3 = xlsx_rows(os.path.join(export_dir, "总体统计.xlsx"), "xl/worksheets/sheet3.xml")
    p3 = {}
    for r in s3:
        if len(r) > 3 and r[1] in ("ALL", "IRONCLAD", "THE_SILENT", "DEFECT", "WATCHER"):
            p3[r[1]] = r
    r3 = p3.get(cls)
    check(rows, "总删牌数", num(r3[2]), got["purge"]["total"], 0)
    check(rows, "商店删牌", num(r3[4]), got["purge"]["shop"], 0)
    check(rows, "事件删牌", num(r3[6]), got["purge"]["event"], 0)
    check(rows, "篝火删牌", num(r3[8]), got["purge"]["campfire"], 0)
    check(rows, "尼奥斯删牌", num(r3[9]), got["purge"]["neow"], 0)
    check(rows, "空鸟笼删牌", num(r3[10]), got["purge"]["cage"], 0)

    # --- 卡牌抓率（data.csv 第一幕分组，抽提供次数最多的 5 张）
    with io.open(os.path.join(export_dir, "data.csv"), encoding="utf-8-sig") as fh:
        csv_rows = [r for r in csv.DictReader(fh) if r["分组"] == "第一幕卡牌选取率"]
    csv_rows.sort(key=lambda r: -int(r["提供次数"]))
    print("卡牌抓率（第一幕，提供次数前 5）：")
    csv_rows = [r for r in csv_rows if r["英文名"].lower() != "skip"]
    for r in csv_rows[:5]:
        base = CARD_RENAMES.get(norm_name(r["英文名"]), norm_name(r["英文名"]))
        picked, offered = got["card_act1"].get(base, [0, 0])
        mark = "OK" if (picked == int(r["选取次数"]) and offered == int(r["提供次数"])) else "DIFF"
        print(f"  {r['英文名']:<22} 导出 {r['选取次数']}/{r['提供次数']:<4} "
              f"复算 {picked}/{offered:<4} {mark}")
        rows.append((f"抓率 {r['英文名']}", int(r['提供次数']), offered, mark, ""))

    # --- 遭遇战战损（sheet1 每个遭遇行）
    print("\n遭遇战平均战损（第一幕前 5）：")
    enc = xlsx_rows(os.path.join(export_dir, "遭遇战伤害.xlsx"))
    seen = 0
    for r in enc:
        if len(r) < 6 or r[0] not in ("第一幕", "第二幕", "第三幕"):
            continue
        act = {"第一幕": 1, "第二幕": 2, "第三幕": 3}[r[0]]
        name_en = r[3]
        if not name_en:
            continue
        vals = got["enc_loss"].get((act, name_en), [])
        exp_mean, exp_n = num(r[4]), num(r[5])
        if exp_n != exp_n or exp_mean != exp_mean:
            continue
        act_mean = sum(vals) / len(vals) if vals else 0
        ok = len(vals) == int(exp_n) and abs(act_mean - exp_mean) < 1e-4
        if act == 1 and seen < 5:
            print(f"  {name_en:<24} 导出 n={int(exp_n):<4} mean={exp_mean:<8.3f} "
                  f"复算 n={len(vals):<4} mean={act_mean:<8.3f} {'OK' if ok else 'DIFF'}")
            seen += 1
        rows.append((f"战损 {act}-{name_en}", exp_mean, round(act_mean, 6), "OK" if ok else "DIFF", f"n={len(vals)}"))

    # --- 每幕战斗总计（按局平均）
    print("\n每幕战斗总计（按发生率平均）：")
    for r in enc:
        if len(r) < 6 or r[1] not in ("战斗总计", "非Boss战斗总计"):
            continue
        act = {"第一幕": 1, "第二幕": 2, "第三幕": 3}.get(r[0], 0)
        if not act:
            continue
        vals = got["all_loss" if r[1] == "战斗总计" else "nonboss_total"].get(act, [])
        mean = sum(vals) / len(vals) if vals else 0
        ok = len(vals) == int(num(r[4], 0)) and abs(mean - num(r[3], -1)) < 1e-4
        if act == 1:
            print("  %-12s 导出 n=%d mean=%.3f | 复算 n=%d mean=%.3f %s"
                  % (r[1], int(num(r[4], 0)), num(r[3], 0), len(vals), mean, "OK" if ok else "DIFF"))
        rows.append(("总战损 %s-%s" % (r[0], r[1]), num(r[3], 0), round(mean, 6),
                     "OK" if ok else "DIFF", "n=%d" % len(vals)))

    # --- 怪物危险度（各幕 T0/非 Boss 分档数量 + 均值）
    print("\n怪物危险度（第一幕）：")
    mt = xlsx_rows(os.path.join(export_dir, "怪物危险度.xlsx"))
    tier_rows = [r for r in mt if len(r) >= 12 and r[0] in ("T0", "T1", "T2", "T3", "T4") and r[5]]
    appendix = [r for r in mt if len(r) >= 12 and r[0] == "—" and r[5]]
    gated = got["tiers"]  # 本脚本 compute() 里已按 gate 复算（见 --gate-on）
    exp_n = len(tier_rows)
    got_n = len(gated[1]["bosses"]) + len(gated[1]["nonboss"])
    print(f"  导出分档行 {exp_n} 个，附录 {len(appendix)} 个；复算(按导出时的门槛) {got_n} 个")
    rows.append(("梯度-第一幕分档条目数", exp_n, got_n, "OK" if exp_n == got_n else "DIFF", ""))
    by_name = {}
    for r in tier_rows + appendix:
        by_name[r[5]] = (r[0] if r[0] != "—" else "low", num(r[6]), num(r[11]))
    okc = 0
    bad = []
    for item in gated[1]["bosses"] + gated[1]["nonboss"]:
        e = by_name.get(item["name"])
        if not e:
            bad.append(item["name"] + "(缺)")
            continue
        if e[0] == item["tier"] and int(e[2]) == item["n"] and abs(e[1] - item["mean"]) < 1e-4:
            okc += 1
        else:
            bad.append("%s 导出%s/复算%s n%s/%s" % (item["name"], e[0], item["tier"], int(e[2]), item["n"]))
    print("  逐项一致 %d/%d" % (okc, got_n))
    if bad:
        print("  不一致:", bad[:6])
    rows.append(("梯度-第一幕逐项一致", got_n, okc, "OK" if okc == got_n else "DIFF", ""))

    # --- 全角色汇总行
    all_runs = []
    for c in ("IRONCLAD", "THE_SILENT", "DEFECT", "WATCHER"):
        all_runs += load_class(c, runs_root, start, end, abandons)
    print(f"\n全角色汇总：原始 {len(all_runs)} 局，导出的 ALL 行 = {num(char_rows['ALL'][3])}")
    rows.append(("ALL 行对局数", len(all_runs), num(char_rows["ALL"][3]),
                 "OK" if len(all_runs) == num(char_rows["ALL"][3]) else "DIFF", ""))

    print("\n%-28s %14s %14s %6s" % ("校验项", "导出", "复算", "结果"))
    print("-" * 70)
    for label, exp, act, st, note in rows:
        e = f"{exp:.4f}" if isinstance(exp, float) else str(exp)
        a = f"{act:.4f}" if isinstance(act, float) else str(act)
        print("%-28s %14s %14s %6s %s" % (label, e, a, st, note))
    bad = [r for r in rows if r[3] == "DIFF"]
    print(f"\n合计 {len(rows)} 项，DIFF {len(bad)} 项")
    return 0


if __name__ == "__main__":
    sys.exit(main())
