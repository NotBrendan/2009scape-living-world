#!/usr/bin/env python3
"""batch 19a A9: read-only look at failure records and bugs (docs/failure-format.md). Never writes anything.

usage: py -3 -I bugs.py [--dir <data/survey>] --top [N]            open bugs by 24 h, then 7 days (from bugs.json)
       py -3 -I bugs.py [--dir <data/survey>] --top [N] --domain D  the same for one domain (path economy quest combat skilling
                                                                   lifecycle health save; batch 19c), with the bug's replay kind
       py -3 -I bugs.py [--dir <data/survey>] --bug <id>           one bug's entry and its records in failures/*.jsonl
       py -3 -I bugs.py --reclassify <failures .jsonl> [...]       re-runs the path rules (batch19-design 4) on each record and
                                                                   prints every record whose reason or bug id comes out different;
                                                                   other domains are "detector-decided" (not re-derived)
The rules below mirror Failures.classify()/bugId() exactly; a rule change goes in both places.
"""
import argparse, datetime, glob, json, os, sys


def read_jsonl(p):
    out = []
    with open(p, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                out.append(json.loads(line))
            except ValueError:
                pass  # a torn line: skipped, as the server does
    return out


# ------------------------------------------------------------------ the path rules (Failures.classify)
def cheb(a, b):
    if a is None or b is None:
        return 99
    return max(abs(a[0] - b[0]), abs(a[1] - b[1]))


def sign(v):
    return (v > 0) - (v < 0)


def toward(s, t):
    return [s[0] + sign(t[0] - s[0]), s[1] + sign(t[1] - s[1]), s[2]]


def same(a, b):
    return a is not None and b is not None and list(a[:3]) == list(b[:3])


def gate_name(n):
    l = (n or "").lower()
    return "trapdoor" not in l and ("gate" in l or "door" in l or "portcullis" in l)


def line(s, t):
    out, c = [], s
    for _ in range(3):
        if c[0] == t[0] and c[1] == t[1]:
            break
        c = toward(c, t); out.append(c)
    return out


def classify(r):
    if r.get("domain", "path") != "path":
        return r.get("reason")  # detector-decided
    why = (r.get("why") or "").lower(); src = r.get("src") or ""
    if src.startswith("replay_") and why.startswith("{replay:") and "/" in why and "} " in why:  # batch 19c: the replayer's reason hint (Scenarios.replayTag)
        code = why[8:why.index("/")]
        if code != "-":
            return code
    cb = r.get("combat") or {}; mv = r.get("mv") or {}; plan = r.get("plan") or {}; near = r.get("near") or {}; gate = r.get("gate") or {}
    objs = near.get("objs") or []; npcs = near.get("npcs") or []
    s = r.get("stall"); to = r.get("to"); nxt = plan.get("next"); drv = r.get("drv") or "none"; ans = plan.get("ans") or "none"
    tgt = nxt if nxt is not None else to
    if tgt is not None and s is not None and tgt[2] != s[2]:
        tgt = None
    if cb.get("died") or "died" in why or "crumbled" in why:
        return "death"
    if cb.get("in") or (cb.get("h60") or 0) >= 3 or (src == "replay_skip" and "attacked" in why):
        return "combat-interrupt"
    deadline = src == "replay_timeout" or (src == "never_reached" and "deadline" in why) or (src == "failed_trip" and "timed out" in why) or (src == "quest_fail" and "time limit" in why)
    if deadline and (mv.get("d24") or 0) >= 10:
        return "timeout"
    if ans == "outside" and not gate.get("box"):
        return "outside-grid"
    if gate.get("id") is not None and gate.get("unmet"):
        return "rule-gate"
    if s is None:
        return "unknown"
    if tgt is not None:
        for o in objs:
            if cheb(o["at"], s) > 2 or o.get("st") != "closed" or o.get("rule") is not None or not ((o.get("ty") or 0) < 4 or gate_name(o.get("n"))):
                continue
            dot = (o["at"][0] - s[0]) * (tgt[0] - s[0]) + (o["at"][1] - s[1]) * (tgt[1] - s[1])
            if dot >= 0 and cheb(o["at"], tgt) <= cheb(s, tgt):
                return "door-closed"
    if tgt is not None and (near.get("run") or 0) >= 3:
        step = toward(s, tgt)
        if any(same(n["at"], step) or same(n["at"], nxt) for n in npcs):
            return "npc-blocking"
    if ans == "no-route" or plan.get("split"):
        return "no-route"
    if mv.get("px16") or drv == "climber" or (to is not None and to[2] != s[2]):
        return "handoff"
    if drv == "planner" and nxt is not None and cheb(nxt, s) <= 3 and mv.get("near3"):
        across = any(o.get("st") == "solid" and not same(o["at"], s) and cheb(o["at"], s) <= cheb(nxt, s) and cheb(o["at"], nxt) <= cheb(nxt, s) for o in objs)
        if mv.get("wall") or across:
            return "wrong-side-hop"
    if ((mv.get("d29") or 0) <= 4 and (mv.get("m29") or 0) >= 16) or mv.get("redir2"):
        return "oscillation"
    cells = [s] + (line(s, tgt) if tgt is not None else [[s[0] + dx, s[1] + dy, s[2]] for dx in (-1, 0, 1) for dy in (-1, 0, 1) if dx or dy])
    if nxt is not None:
        cells.append(nxt)
    door = any(o.get("st") == "closed" and cheb(o["at"], s) <= 2 for o in objs)
    if not door:
        for o in objs:
            if o.get("st") != "solid" or not any(same(o["at"], c) for c in cells):
                continue
            if not any(same(n["at"], o["at"]) for n in npcs):
                return "blocked-object"
    if (drv == "old" or (drv == "routemap" and nxt is not None and cheb(nxt, s) > 2)) and (mv.get("still") or 0) >= 60:
        return "engine-pathfinder-giveup"
    return "unknown"


def bug_id(r, reason):
    """Failures.bugId: the bug a record with this reason groups into (None for unknown)."""
    if reason is None or reason == "unknown":
        return None
    if reason == "exception":
        e = r.get("exc") or {}
        return "b-exception-%s-%s" % (e.get("where"), (e.get("cls") or "Throwable").split(".")[-1])
    if r.get("domain", "path") != "path":
        return r.get("bug")  # detector-decided key
    dest = reason in ("no-route", "outside-grid", "timeout") and r.get("to") is not None
    at = r.get("to") if dest else r.get("stall")
    if at is None:
        return "b-%s-x" % reason
    bid = "b-%s-%s%d,%d,%d" % (reason, "dest-" if dest else "", at[0] >> 4, at[1] >> 4, at[2])
    gate = r.get("gate") or {}
    if reason == "rule-gate" and gate.get("id"):
        bid += "-" + gate["id"]
    return bid


# ------------------------------------------------------------------ commands
def utc(ms):
    return datetime.datetime.fromtimestamp(ms / 1000, datetime.timezone.utc).strftime("%Y-%m-%d")


def top(d, n, domain=None):
    with open(os.path.join(d, "bugs.json"), encoding="utf-8") as f:
        bugs = json.load(f).get("bugs") or {}
    today = utc(datetime.datetime.now(datetime.timezone.utc).timestamp() * 1000)
    week = [(datetime.date.fromisoformat(today) - datetime.timedelta(days=i)).isoformat() for i in range(7)]
    rows = []
    for b in bugs.values():
        if b.get("status") != "open":
            continue
        if domain and b.get("domain", "path") != domain:  # batch 19c
            continue
        days = b.get("days") or {}
        rows.append((days.get(today, 0), sum(days.get(x, 0) for x in week), b))
    rows.sort(key=lambda x: (-x[0], -x[1], x[2]["id"]))
    print("| bug | domain | 24 h | 7 d | count | place | reason / detail | example | replay |")
    print("|---|---|---|---|---|---|---|---|---|")
    for a, w, b in rows[:n]:
        ex = (b.get("examples") or ["-"])[-1]
        rp = b.get("replay") if isinstance(b.get("replay"), dict) else {}
        print("| %s | %s | %d | %d | %d | %s | %s / %s | %s | %s |" % (b["id"], b.get("domain", "path"), a, w, b.get("count", 0), b.get("place", ""), b.get("reason"), b.get("rsub", ""), ex, rp.get("kind", "-")))
    st = {}
    for b in bugs.values():
        st[b.get("status")] = st.get(b.get("status"), 0) + 1
    print("\n%d bugs: %s" % (len(bugs), ", ".join("%s %d" % kv for kv in sorted(st.items(), key=lambda kv: str(kv[0])))))
    if domain:
        print("%d open in domain %s" % (len(rows), domain))


def one(d, bid):
    p = os.path.join(d, "bugs.json")
    if os.path.exists(p):
        with open(p, encoding="utf-8") as f:
            b = (json.load(f).get("bugs") or {}).get(bid)
        print(json.dumps(b, indent=1) if b else "%s: not in bugs.json" % bid)
    n = 0
    for fp in sorted(glob.glob(os.path.join(d, "failures", "*.jsonl"))):
        for r in read_jsonl(fp):
            if r.get("bug") == bid:
                n += 1
                print("%s %s %s %s %s stall %s: %s (%s)" % (os.path.basename(fp)[:10], r.get("fid"), r.get("src"), r.get("bot"), r.get("drv", ""), r.get("stall"), r.get("reason"), r.get("rsub")))
    print("%d records" % n)


def reclassify(files):
    total = diffs = other = 0
    for fp in files:
        for r in read_jsonl(fp):
            total += 1
            if r.get("domain", "path") != "path" or r.get("src") == "exception":
                other += 1
                continue
            reason = classify(r); bid = bug_id(r, reason)
            if reason != r.get("reason") or bid != r.get("bug"):
                diffs += 1
                print("DIFF %s %s: file %s / %s, rules %s / %s%s" % (r.get("fid"), r.get("bot"), r.get("reason"), r.get("bug"), reason, bid, " (trimmed %s)" % r["trim"] if r.get("trim") else ""))
    print("reclassify: %d records, %d path re-derived, %d detector-decided or exceptions, %d diffs" % (total, total - other, other, diffs))
    return diffs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default=os.path.join("data", "survey"))
    ap.add_argument("--top", nargs="?", const=20, type=int)
    ap.add_argument("--bug")
    ap.add_argument("--reclassify", nargs="+")
    ap.add_argument("--domain", choices=["path", "economy", "quest", "combat", "skilling", "lifecycle", "health", "save"], help="batch 19c: --top for one domain")
    a = ap.parse_args()
    if a.reclassify:
        sys.exit(1 if reclassify(a.reclassify) else 0)
    if a.bug:
        one(a.dir, a.bug); return
    top(a.dir, a.top or 20, a.domain)


if __name__ == "__main__":
    main()
