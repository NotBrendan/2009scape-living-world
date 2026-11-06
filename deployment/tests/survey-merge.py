#!/usr/bin/env python3
"""Survey farm merge (spare PC, batch 18): combine the farm worlds' learned survey files with the live seed into one candidate.

usage: py -3 -I survey-merge.py [--layout <layout.json>] [--date YYYY-MM-DD] [--worlds <dir> ...]
  Paths come from <farm root>/farm/layout.json (liveSeed, results); never hardcoded. Inputs: liveSeed/data/survey and each
  <results>/survey/<world>/ folder (a copy of that world's data/survey). Output: <results>/survey/merged-<date>/data/survey/ plus
  counts.json and counts.md (entries in the seed, new from the farm, per file and per world).

Rules: nothing live learned is removed or overwritten with less. Only what the survey roles learn travels:
  stairs.jsonl, doors.jsonl, shortcuts.jsonl, offgrid.jsonl, scenario-results.jsonl  append-only logs: every seed line, then the
      farm's lines that prove something not already there (fact(): the same place/object/option/result counts once), in time order;
  gates.json   the seed's gates/shutDoors/scannedRegions/shortcuts, plus farm entries with new keys; a shortcut both have is taken
      from the farm only if it was tested later there (its fields merged over the seed's, so no live field is lost);
  tiles.json   the tile census: every seed tile kept, new tiles added, a shared tile keeps the higher count.
  bugs.json    batch 19a (the design notes 5.3, docs/failure-format.md): seed = the live copy (the newest pulled
      <scenarios>/live-*/bugs.json, else liveSeed's; --bugs-seed overrides). Counts (count, days, sources, examples, fids, bots, first,
      last, snap) are live-only: a farm entry never changes them, and farm-only ids are ignored. Replays are a union by (t, world, bot),
      newest 10. Status: owner > live > farm; the farm can only move open -> not-reproduced (the newest 3 replays in 7 days pass and
      no live record since the oldest of them), and only when live did not record the bug after pulled.txt's time; otherwise the
      proposal goes into replays as result "proposal" and the status stays. Loss check: every seed id present with count, first,
      note and an owner status unchanged, else exit 2 (like the line-loss check). --pulled <pulled.txt> overrides the pull time.
      batch 19c (batch19c-design 9.2): entries carry "domain" and "replay" (the farm replay spec) through the merge unchanged; a seed
      entry that lacks either takes the farm's (additive: no count, status or note moves). Economy/quest/... ids merge by the same rules.
Never travel: heat.json, eco traps*.json (live-only learning), routemap.json and legs.jsonl (live routes; the farm's smart bots walk a
simulated world), resources.json, nav-*.bin/nav-baseline.txt and switch files, scenarios.jsonl (live incidents only come from live).
"""
import argparse, datetime, json, os, shutil, sys

LOGS = ["stairs.jsonl", "doors.jsonl", "shortcuts.jsonl", "offgrid.jsonl", "scenario-results.jsonl"]


def layout(path):
    if path is None:
        here = os.path.dirname(os.path.abspath(__file__))
        path = os.path.join(here, "..", "..", "..", "farm", "layout.json")
    with open(path, encoding="utf-8-sig") as f:
        return json.load(f)


def fact(f, line):
    """What a line proves, so the same place found again (another world, a later run) is one entry: offgrid by its key, climbs, doors and
    shortcuts by object, tile, option and result (shortcuts also by agility band), results by scenario and result."""
    try:
        j = json.loads(line)
    except ValueError:
        return line
    at = tuple(j.get("at") or [])
    if f == "offgrid.jsonl":
        return ("o", j.get("key"), j.get("success"))
    if f in ("stairs.jsonl", "doors.jsonl"):
        return (f, at, j.get("id"), j.get("option"), j.get("result"))
    if f == "shortcuts.jsonl":
        return (f, at, j.get("id"), j.get("option"), j.get("result"), (j.get("agility") or 0) >= 50)
    if f == "scenario-results.jsonl":
        return ("r", j.get("id"), j.get("result"))
    return line


def read_lines(p):
    if not os.path.exists(p):
        return []
    out = []
    with open(p, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                json.loads(line)
            except ValueError:
                continue  # a torn line: skipped, as the server does
            out.append(line)
    return out


def t_of(line):
    try:
        return int(json.loads(line).get("t", 0))
    except Exception:
        return 0


def load_json(p):
    if not os.path.exists(p):
        return None
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def sc_key(s):
    return "%s,%s,%s#%s" % (s.get("x"), s.get("y"), s.get("z"), s.get("id"))


def sc_time(s):
    return max(int(s.get(k) or 0) for k in ("tested", "lowT", "highT"))


def merge_gates(seed, farms, counts):
    out = json.loads(json.dumps(seed)) if seed else {"gates": [], "shutDoors": {}, "scannedRegions": [], "shortcuts": []}
    n = {"gates": 0, "shutDoors": 0, "scannedRegions": 0, "shortcuts new": 0, "shortcuts retested": 0}
    gid = {g.get("id") for g in out.get("gates", [])}
    reg = set(out.get("scannedRegions", []))
    scs = {sc_key(s): i for i, s in enumerate(out.get("shortcuts", []))}
    for world, g in farms:
        if not g:
            continue
        for x in g.get("gates", []):
            if x.get("id") not in gid:
                out.setdefault("gates", []).append(x); gid.add(x.get("id")); n["gates"] += 1
        for k, v in (g.get("shutDoors") or {}).items():
            if k not in out.setdefault("shutDoors", {}):
                out["shutDoors"][k] = v; n["shutDoors"] += 1
        for r in g.get("scannedRegions", []):
            if r not in reg:
                out.setdefault("scannedRegions", []).append(r); reg.add(r); n["scannedRegions"] += 1
        for s in g.get("shortcuts", []):
            k = sc_key(s)
            if k not in scs:
                scs[k] = len(out.setdefault("shortcuts", [])); out["shortcuts"].append(s); n["shortcuts new"] += 1
            else:
                cur = out["shortcuts"][scs[k]]
                if sc_time(s) > sc_time(cur):
                    merged = dict(cur); merged.update(s); out["shortcuts"][scs[k]] = merged; n["shortcuts retested"] += 1
    counts["gates.json"] = {"seed": {k: len(v) for k, v in (seed or {}).items() if isinstance(v, (list, dict))}, "new": n}
    return out


DAY_MS = 86400000


def now_ms():
    return int(datetime.datetime.now(datetime.timezone.utc).timestamp() * 1000)


def pulled_info(lay, explicit=None):
    """batch 19a: (pull time in ms or None, the pull folder or None): --pulled, else the newest <scenarios>/live-*/pulled.txt."""
    p = explicit
    if p is None and lay.get("scenarios") and os.path.isdir(lay["scenarios"]):
        cands = sorted(d for d in os.listdir(lay["scenarios"]) if d.startswith("live-") and os.path.exists(os.path.join(lay["scenarios"], d, "pulled.txt")))
        if cands:
            p = os.path.join(lay["scenarios"], cands[-1], "pulled.txt")
    if p is None or not os.path.exists(p):
        return None, None
    with open(p, encoding="utf-8") as f:
        for line in f:
            if line.startswith("pulled: "):
                stamp = line.split()[1]
                try:
                    t = datetime.datetime.strptime(stamp, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=datetime.timezone.utc)
                    return int(t.timestamp() * 1000), os.path.dirname(p)
                except ValueError:
                    break
    return None, os.path.dirname(p)


def rkey(r):
    return (int(r.get("t") or 0), r.get("world"), r.get("bot"))


def union_replays(a, b):
    """Union by (t, world, bot), newest 10 kept, oldest first (Failures.Bugs.replays)."""
    u = {}
    for r in (a or []) + (b or []):
        u.setdefault(rkey(r), r)
    return sorted(u.values(), key=lambda r: int(r.get("t") or 0))[-10:]


def not_reproduced(e, now):
    """open -> not-reproduced: the newest 3 replays within 7 days all pass and no live record since the oldest of them (Failures.Bugs.notReproduced)."""
    rs = [r for r in e.get("replays") or [] if now - int(r.get("t") or 0) <= 7 * DAY_MS and r.get("result") != "proposal"]
    if len(rs) < 3:
        return False
    rs.sort(key=lambda r: -int(r.get("t") or 0))
    top = rs[:3]
    if any(r.get("result") != "pass" for r in top):
        return False
    return int(e.get("last") or 0) <= min(int(r.get("t") or 0) for r in top)


def merge_bugs(seed, farms, counts, pulled, now=None):
    """batch 19a 5.3: the live seed plus the farm worlds' replays and open -> not-reproduced proposals. Counts are never taken from a farm."""
    now = now or now_ms()
    out = json.loads(json.dumps(seed)) if seed else {"v": 1, "bugs": {}}
    out["v"] = out.get("v", 1); out["host"] = "live"; out["updated"] = now
    bugs = out.setdefault("bugs", {})
    n = {"seed": len(bugs), "replays added": 0, "status proposals": 0, "moved to not-reproduced": 0, "proposals kept as replays": 0, "farm-only ids ignored": 0}
    touched = set()
    for world, f in farms:
        if not f:
            continue
        wname = "farm-" + os.path.basename(os.path.normpath(world))
        for bid, fe in (f.get("bugs") or {}).items():
            be = bugs.get(bid)
            if be is None:
                n["farm-only ids ignored"] += 1
                continue
            for k in ("domain", "replay"):  # batch 19c: carried, never overwritten
                if k not in be and fe.get(k) is not None:
                    be[k] = fe[k]
            have = {rkey(r) for r in be.get("replays") or []}
            reps = [dict(r, world=r.get("world") or wname) for r in fe.get("replays") or []]
            be["replays"] = union_replays(be.get("replays"), reps)
            n["replays added"] += len({rkey(r) for r in be["replays"]} - have)
            if fe.get("status") == "not-reproduced" and be.get("status") == "open":
                n["status proposals"] += 1
            touched.add(bid)
    for bid in touched:
        be = bugs[bid]
        if be.get("status") != "open" or be.get("statusBy") == "owner" or not not_reproduced(be, now):
            continue
        if pulled is not None and int(be.get("last") or 0) > pulled:
            be["replays"] = union_replays(be.get("replays"), [{"t": now, "world": "farm-merge", "bot": "-", "sid": None, "result": "proposal", "fid": None,
                                                                "why": "not-reproduced proposed by the farm; live recorded it after the pull"}])
            n["proposals kept as replays"] += 1
        else:
            be["status"] = "not-reproduced"; be["statusBy"] = "farm"; be["statusT"] = now
            n["moved to not-reproduced"] += 1
    counts["bugs.json"] = n
    return out


def check_bugs(seed, merged):
    """batch 19a 5.3 rule 4: every seed id present with count, first, note and an owner status unchanged. Returns the problems."""
    bad = []
    mb = (merged or {}).get("bugs") or {}
    for bid, s in ((seed or {}).get("bugs") or {}).items():
        m = mb.get(bid)
        if m is None:
            bad.append("%s dropped" % bid); continue
        for k in ("count", "first", "note"):
            if m.get(k) != s.get(k):
                bad.append("%s %s changed %r -> %r" % (bid, k, s.get(k), m.get(k)))
        if s.get("statusBy") == "owner" and (m.get("status") != s.get("status") or m.get("statusBy") != "owner"):
            bad.append("%s owner status changed %r -> %r" % (bid, s.get("status"), m.get("status")))
    return bad


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--layout"); ap.add_argument("--date", default=datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d"))
    ap.add_argument("--worlds", nargs="*", help="world folders (default: every folder in <results>/survey/ that is not merged-*)")
    ap.add_argument("--pulled", help="batch 19a: the pulled.txt whose time gates the farm's status proposals (default: the newest)")
    ap.add_argument("--bugs-seed", help="batch 19a: the live bugs.json to merge into (default: next to that pulled.txt, else liveSeed's)")
    a = ap.parse_args()
    lay = layout(a.layout)
    seed = os.path.join(lay["liveSeed"], "data", "survey")
    base = os.path.join(lay["results"], "survey")
    worlds = a.worlds or sorted(os.path.join(base, d) for d in os.listdir(base) if os.path.isdir(os.path.join(base, d)) and not d.startswith("merged-"))
    out = os.path.join(base, "merged-" + a.date)
    od = os.path.join(out, "data", "survey")
    if os.path.exists(out):
        shutil.rmtree(out)
    os.makedirs(od)
    counts = {"date": a.date, "seed": seed, "worlds": worlds, "files": {}}
    per_world = {}
    for f in LOGS:
        s = read_lines(os.path.join(seed, f)); have = {fact(f, x) for x in s}; new = []
        for w in worlds:
            c = 0
            for line in read_lines(os.path.join(w, f)):
                k = fact(f, line)
                if k not in have:
                    have.add(k); new.append(line); c += 1
            per_world.setdefault(os.path.basename(w), {})[f] = c
        new.sort(key=t_of)
        if s or new:
            with open(os.path.join(od, f), "w", encoding="utf-8", newline="\n") as o:
                for line in s + new:
                    o.write(line + "\n")
        counts["files"][f] = {"seed": len(s), "new": len(new), "merged": len(s) + len(new)}
    g = merge_gates(load_json(os.path.join(seed, "gates.json")), [(w, load_json(os.path.join(w, "gates.json"))) for w in worlds], counts["files"])
    with open(os.path.join(od, "gates.json"), "w", encoding="utf-8") as o:
        json.dump(g, o)
    tiles = load_json(os.path.join(seed, "tiles.json")) or {}
    st = len(tiles); added = raised = 0
    for w in worlds:
        for k, v in (load_json(os.path.join(w, "tiles.json")) or {}).items():
            if k not in tiles:
                tiles[k] = v; added += 1
            elif isinstance(v, int) and v > tiles[k]:
                tiles[k] = v; raised += 1
    with open(os.path.join(od, "tiles.json"), "w", encoding="utf-8") as o:
        json.dump(tiles, o)
    counts["files"]["tiles.json"] = {"seed": st, "new": added, "higher count": raised, "merged": len(tiles)}
    # batch 19a: bugs.json (5.3), next to gates.json, not in LOGS
    pulled, pdir = pulled_info(lay, a.pulled)
    bseed_path = a.bugs_seed or (os.path.join(pdir, "bugs.json") if pdir and os.path.exists(os.path.join(pdir, "bugs.json")) else os.path.join(seed, "bugs.json"))
    bseed = load_json(bseed_path)
    bfarms = [(w, load_json(os.path.join(w, "bugs.json"))) for w in worlds]
    if bseed is not None or any(f for _, f in bfarms):
        mb = merge_bugs(bseed, bfarms, counts["files"], pulled)
        counts["files"]["bugs.json"]["seed file"] = bseed_path; counts["files"]["bugs.json"]["pulled"] = pulled
        with open(os.path.join(od, "bugs.json"), "w", encoding="utf-8") as o:
            json.dump(mb, o)
    counts["per_world_new_lines"] = per_world
    # the candidate must hold every live line: check before calling it done
    for f in LOGS:
        sl = set(read_lines(os.path.join(seed, f))); ml = set(read_lines(os.path.join(od, f)))
        if not sl <= ml:
            print("ERROR: %s lost %d live lines" % (f, len(sl - ml))); sys.exit(2)
    if bseed is not None:
        bad = check_bugs(bseed, load_json(os.path.join(od, "bugs.json")))
        if bad:
            print("ERROR: bugs.json lost live data: %s" % "; ".join(bad[:10])); sys.exit(2)
    with open(os.path.join(out, "counts.json"), "w", encoding="utf-8") as o:
        json.dump(counts, o, indent=2)
    with open(os.path.join(out, "counts.md"), "w", encoding="utf-8") as o:
        o.write("# Survey merge %s\n\nSeed: `%s`. Worlds: %s.\n\n| file | in the seed | new from the farm | merged |\n|---|---|---|---|\n" % (a.date, seed, ", ".join(os.path.basename(w) for w in worlds)))
        for f, c in counts["files"].items():
            if f == "gates.json":
                o.write("| gates.json | %s | %s | |\n" % (", ".join("%s %d" % kv for kv in c["seed"].items()), ", ".join("%s %d" % kv for kv in c["new"].items())))
            elif f == "bugs.json":
                o.write("| bugs.json | %d bugs | replays added %d, status proposals %d (moved %d, kept as replays %d), farm-only ids ignored %d | %d |\n" % (
                    c["seed"], c["replays added"], c["status proposals"], c["moved to not-reproduced"], c["proposals kept as replays"], c["farm-only ids ignored"], c["seed"]))
            else:
                o.write("| %s | %d | %d%s | %d |\n" % (f, c["seed"], c["new"], (" (+%d higher counts)" % c["higher count"]) if "higher count" in c else "", c["merged"]))
        o.write("\nNew lines per world: %s\n" % json.dumps(per_world))
        if "bugs.json" in counts["files"]:
            c = counts["files"]["bugs.json"]
            o.write("\nbugs: seed %d, replays added %d, status proposals %d\n" % (c["seed"], c["replays added"], c["status proposals"]))
    print(json.dumps(counts["files"], indent=1))
    print("merged candidate:", out)


if __name__ == "__main__":
    main()
