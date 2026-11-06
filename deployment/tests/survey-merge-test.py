#!/usr/bin/env python3
"""batch 19a A7: fixtures for survey-merge.py's bugs.json merge (the design notes 5.3). Read-only on the project: every
file lives in a temporary folder that is removed afterwards.

usage: py -3 -I survey-merge-test.py      -> one "ok"/"FAIL" line per check, "RESULT n/m", exit 0 only when all pass.

Fixture: a live seed (liveSeed/data/survey) with stairs.jsonl and the pulled live bugs.json (<scenarios>/live-<date>/ with
pulled.txt), two farm worlds (<results>/survey/wA, wB) with bugs.json and stairs.jsonl. Bugs:
  b-owner  owner status wont-fix + a note; the farms report 3 passes and "not-reproduced"   -> stays wont-fix, note and count kept
  b-many   open; wA 8 replays, wB 6 (2 shared with wA); the seed 1                         -> union by (t, world, bot), newest 10
  b-fixed  open, last live record before the passes, 3 passes (wA 2, wB 1)                -> not-reproduced by farm
  b-late   open, live recorded it after pulled.txt's time, then 3 passes                 -> stays open, a "proposal" replay added
  b-fail   open, newest of the 3 replays is a fail                                        -> stays open
  b-farm   only on the farm (farm incidents)                                              -> not in the output (counts are live-only)
  counts   the farm's count for b-many is 999                                             -> the seed's count kept
  b-shop-loop-wydin-s   batch 19c: domain economy with a replay spec; wA 3 passes          -> not-reproduced by farm, domain and replay kept
  b-step-stalled-cooks-2 batch 19c: domain quest, the seed without a replay spec, the farm has one -> the spec carried, count kept
Loss check: merge_bugs patched to drop b-many -> survey-merge exits 2; batch 19c: dropping the economy id exits 2 too. Existing
behaviour: stairs lines merged as before.
"""
import importlib.util, json, os, shutil, subprocess, sys, tempfile, time

HERE = os.path.dirname(os.path.abspath(__file__))
SHOP_SPEC = {"kind": "shop", "shop": "wydin-s-food-store", "buy": [[1933, 1]], "from": [3092, 3243, 0], "coins": 500}  # batch 19c fixtures
QUEST_SPEC = {"kind": "quest", "slug": "cooks", "step": 2, "stage": 10}
SCRIPT = os.path.join(HERE, "survey-merge.py")
DAY = 86400000
checks = []


def check(name, ok, detail=""):
    checks.append(bool(ok))
    print(("ok   " if ok else "FAIL ") + name + ((": " + detail) if detail else ""))


def rep(t, world, bot, result, sid="s1"):
    return {"t": t, "world": world, "bot": bot, "sid": sid, "result": result, "fid": None, "why": ""}


def entry(bid, count, first, last, status="open", by="live", note="", replays=None, domain="path", reason="door-closed"):
    return {"id": bid, "domain": domain, "reason": reason, "rsub": "", "cell": [200, 200, 0], "place": "x", "first": first, "last": last,
            "count": count, "days": {"2026-10-25": count}, "sources": {"rescue": count}, "examples": ["s1"], "fids": [], "bots": ["Bot1"], "snap": {"role": "adventurer"},
            "status": status, "statusT": first, "statusBy": by, "note": note, "replays": replays or []}


def write(p, obj):
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8") as f:
        if isinstance(obj, str):
            f.write(obj)
        else:
            json.dump(obj, f)


def main():
    now = int(time.time() * 1000)
    tmp = tempfile.mkdtemp(prefix="survey-merge-test-")
    try:
        seed = os.path.join(tmp, "seed"); results = os.path.join(tmp, "results"); scen = os.path.join(tmp, "scenarios")
        lay = {"liveSeed": seed, "results": results, "scenarios": scen}
        write(os.path.join(tmp, "layout.json"), lay)
        pulled = now - 3 * 3600_000  # the pull was 3 hours ago
        pd = os.path.join(scen, "live-2026-10-25")
        write(os.path.join(pd, "pulled.txt"), "pulled: %s from test (read-only)\n" % time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(pulled / 1000)))
        sd = os.path.join(seed, "data", "survey")
        write(os.path.join(sd, "stairs.jsonl"), json.dumps({"t": 1, "at": [1, 2, 0], "id": 5, "option": "Climb-up", "result": "ok"}) + "\n")
        t0 = now - 2 * DAY
        live = {"v": 1, "host": "live", "bugs": {
            "b-owner": entry("b-owner", 12, t0, t0 + 1000, status="wont-fix", by="owner", note="owner: leave it"),
            "b-many": entry("b-many", 40, t0, t0 + 1000, replays=[rep(t0 + 5000, "live", "R0", "fail")]),
            "b-fixed": entry("b-fixed", 7, t0, t0 + 1000),
            "b-late": entry("b-late", 9, t0, pulled + 60_000),
            "b-fail": entry("b-fail", 3, t0, t0 + 1000),
            "b-shop-loop-wydin-s": dict(entry("b-shop-loop-wydin-s", 6, t0, t0 + 1000, domain="economy", reason="shop-loop"), replay=SHOP_SPEC),  # batch 19c
            "b-step-stalled-cooks-2": entry("b-step-stalled-cooks-2", 4, t0, t0 + 1000, domain="quest", reason="step-stalled"),  # batch 19c: no spec on live yet
        }}
        write(os.path.join(pd, "bugs.json"), live)
        wa = os.path.join(results, "survey", "wA"); wb = os.path.join(results, "survey", "wB")
        tp = now - DAY  # passes a day ago, after the live records of b-fixed
        fa = {"v": 1, "host": "farm-wA", "bugs": {
            "b-owner": entry("b-owner", 1, tp, tp, status="not-reproduced", by="farm", replays=[rep(tp + i, "farm-wA", "RA%d" % i, "pass") for i in range(3)]),
            "b-many": dict(entry("b-many", 999, tp, tp, replays=[rep(tp + 100 + i, "farm-wA", "RA%d" % i, "pass") for i in range(8)])),
            "b-fixed": entry("b-fixed", 1, tp, tp, status="not-reproduced", by="farm", replays=[rep(tp + 1, "farm-wA", "RA1", "pass"), rep(tp + 2, "farm-wA", "RA2", "pass")]),
            "b-late": entry("b-late", 1, tp, tp, replays=[rep(pulled + 120_000 + i, "farm-wA", "RA%d" % i, "pass") for i in range(3)]),  # passes after the pull and after that live record
            "b-fail": entry("b-fail", 1, tp, tp, replays=[rep(tp + 1, "farm-wA", "RA1", "pass"), rep(tp + 2, "farm-wA", "RA2", "pass"), rep(tp + 3, "farm-wA", "RA3", "fail")]),
            "b-farm": entry("b-farm", 5, tp, tp),
            "b-shop-loop-wydin-s": dict(entry("b-shop-loop-wydin-s", 2, tp, tp, domain="economy", reason="shop-loop", replays=[rep(tp + 10 + i, "farm-wA", "EA%d" % i, "pass", sid=None) for i in range(3)]), replay=SHOP_SPEC),
            "b-step-stalled-cooks-2": dict(entry("b-step-stalled-cooks-2", 50, tp, tp, domain="quest", reason="step-stalled", replays=[rep(tp + 20, "farm-wA", "QA1", "fail", sid=None)]), replay=QUEST_SPEC),
        }}
        fb = {"v": 1, "host": "farm-wB", "bugs": {
            "b-many": entry("b-many", 999, tp, tp, replays=[rep(tp + 106, "farm-wA", "RA6", "pass"), rep(tp + 107, "farm-wA", "RA7", "pass")] + [rep(tp + 200 + i, "farm-wB", "RB%d" % i, "pass") for i in range(4)]),
            "b-fixed": entry("b-fixed", 1, tp, tp, replays=[rep(tp + 3, None, "RB1", "pass")]),
        }}
        write(os.path.join(wa, "bugs.json"), fa); write(os.path.join(wb, "bugs.json"), fb)
        write(os.path.join(wa, "stairs.jsonl"), json.dumps({"t": 2, "at": [3, 4, 0], "id": 6, "option": "Climb-down", "result": "ok"}) + "\n")

        r = subprocess.run([sys.executable, "-I", SCRIPT, "--layout", os.path.join(tmp, "layout.json"), "--date", "2026-10-25"], capture_output=True, text=True)
        check("survey-merge exit 0", r.returncode == 0, (r.stdout + r.stderr).strip()[-300:])
        od = os.path.join(results, "survey", "merged-2026-10-25", "data", "survey")
        with open(os.path.join(od, "bugs.json"), encoding="utf-8") as f:
            m = json.load(f)["bugs"]
        b = m["b-owner"]
        check("owner status kept", b["status"] == "wont-fix" and b["statusBy"] == "owner" and b["note"] == "owner: leave it" and b["count"] == 12, "%s/%s count %s" % (b["status"], b["statusBy"], b["count"]))
        b = m["b-many"]; keys = [(x["t"], x["world"], x["bot"]) for x in b["replays"]]
        want = sorted({(t0 + 5000, "live", "R0")} | {(tp + 100 + i, "farm-wA", "RA%d" % i) for i in range(8)} | {(tp + 200 + i, "farm-wB", "RB%d" % i) for i in range(4)})[-10:]
        check("replays: union of the 10 newest", keys == want and len(set(keys)) == 10, "%d replays, newest %s" % (len(keys), keys[-1:]))
        check("counts are live-only", b["count"] == 40 and "b-farm" not in m, "count %s, b-farm %s" % (b["count"], "b-farm" in m))
        b = m["b-fixed"]
        check("3 passes, no live record after them -> not-reproduced by farm", b["status"] == "not-reproduced" and b["statusBy"] == "farm", "%s/%s, replays %d (wB's replay without world -> %s)" % (b["status"], b["statusBy"], len(b["replays"]), b["replays"][-1]["world"]))
        b = m["b-late"]
        check("live recorded after the pull -> stays open, proposal kept as a replay", b["status"] == "open" and any(x["result"] == "proposal" for x in b["replays"]), b["status"])
        check("newest replay a fail -> stays open", m["b-fail"]["status"] == "open")
        b = m["b-shop-loop-wydin-s"]  # batch 19c
        check("19c economy id: 3 farm passes -> not-reproduced by farm, domain and replay kept, count live", b["status"] == "not-reproduced" and b["statusBy"] == "farm" and b["domain"] == "economy" and b.get("replay") == SHOP_SPEC and b["count"] == 6 and len(b["replays"]) == 3,
              "%s/%s domain %s replay %s count %s replays %d" % (b["status"], b["statusBy"], b.get("domain"), b.get("replay"), b["count"], len(b["replays"])))
        b = m["b-step-stalled-cooks-2"]
        check("19c quest id: the farm's replay spec carried into a seed entry without one, count and status live", b.get("replay") == QUEST_SPEC and b["domain"] == "quest" and b["count"] == 4 and b["status"] == "open",
              "replay %s domain %s count %s status %s" % (b.get("replay"), b.get("domain"), b["count"], b["status"]))
        with open(os.path.join(od, "stairs.jsonl"), encoding="utf-8") as f:
            st = f.read().splitlines()
        check("existing behaviour: stairs.jsonl seed + farm line", len(st) == 2, "%d lines" % len(st))
        with open(os.path.join(results, "survey", "merged-2026-10-25", "counts.md"), encoding="utf-8") as f:
            cm = f.read()
        check("counts.md has the bugs line", "bugs: seed 7, replays added" in cm, str([l for l in cm.splitlines() if l.startswith("bugs:")][:1]))

        # loss check: a merge that drops a seed id must exit 2
        spec = importlib.util.spec_from_file_location("survey_merge", SCRIPT); mod = importlib.util.module_from_spec(spec); spec.loader.exec_module(mod)
        real = mod.merge_bugs
        def dropping(*a, **k):
            out = real(*a, **k); out["bugs"].pop("b-many", None); return out
        mod.merge_bugs = dropping
        sys.argv = ["survey-merge.py", "--layout", os.path.join(tmp, "layout.json"), "--date", "2026-10-26"]
        code = None
        try:
            mod.main()
        except SystemExit as e:
            code = e.code
        check("loss check: a dropped seed id exits 2", code == 2, "exit %r" % code)
        mod.merge_bugs = real
        def changing(*a, **k):
            out = real(*a, **k); out["bugs"]["b-owner"]["status"] = "open"; return out
        mod.merge_bugs = changing
        sys.argv = ["survey-merge.py", "--layout", os.path.join(tmp, "layout.json"), "--date", "2026-10-27"]
        code = None
        try:
            mod.main()
        except SystemExit as e:
            code = e.code
        check("loss check: a changed owner status exits 2", code == 2, "exit %r" % code)
        def dropping_econ(*a, **k):  # batch 19c: an economy id is as protected as a path id
            out = real(*a, **k); out["bugs"].pop("b-shop-loop-wydin-s", None); return out
        mod.merge_bugs = dropping_econ
        sys.argv = ["survey-merge.py", "--layout", os.path.join(tmp, "layout.json"), "--date", "2026-10-28"]
        code = None
        try:
            mod.main()
        except SystemExit as e:
            code = e.code
        check("19c loss check: a dropped economy id exits 2", code == 2, "exit %r" % code)
        mod.merge_bugs = real
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    print("RESULT %d/%d" % (sum(checks), len(checks)))
    sys.exit(0 if all(checks) else 1)


if __name__ == "__main__":
    main()
