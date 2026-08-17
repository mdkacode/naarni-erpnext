"""Stress the offline sync endpoint the way a plant network actually breaks it.

Run:
    cd ~/frappe-bench && env/bin/python \
        /private/tmp/.../scratchpad/stress_sync.py

Not a unit test. Every scenario here is two or more requests doing something to
each other, which is the only class of bug that matters for a handset queue:
a single sync working proves nothing about thirty of them arriving at once
because a van full of engineers just drove into signal.

Each worker is a separate process with its own Frappe connection — threads share
one, and a shared connection serialises exactly the contention we are trying to
create.
"""

import multiprocessing as mp
import os
import random
import sys
import time
import traceback

BENCH = os.path.expanduser("~/frappe-bench")
SITE = "dev.localhost"
sys.path.insert(0, os.path.join(BENCH, "apps", "frappe"))
sys.path.insert(0, os.path.join(BENCH, "apps", "vehicle_maintenance"))

CODE = "STRESS-SYNC-PROC"
OUTCOME_SET = "STRESS-SYNC-OUTCOMES"
STEP_COUNT = 59


def _connect():
    # Frappe writes its logs to `../logs` relative to the *working directory*,
    # and bench runs everything from `frappe-bench/sites`. A worker spawned
    # anywhere else dies opening `~/logs/database.log` before it reaches the
    # database, which looks like a connection problem and is not one.
    os.chdir(os.path.join(BENCH, "sites"))
    import frappe

    # A *relative* sites_path, with the chdir above. Frappe resolves the
    # log directory from it too, and an absolute path desynced the two —
    # sending database.log to the parent of the bench, where it died.
    frappe.init(site=SITE, sites_path=".")
    frappe.connect()
    frappe.set_user("Administrator")
    return frappe


# ------------------------------------------------------------------ fixture


def ensure_definition():
    frappe = _connect()
    try:
        if not frappe.db.exists("Process Outcome Set", OUTCOME_SET):
            doc = frappe.get_doc(
                {
                    "doctype": "Process Outcome Set",
                    "set_code": OUTCOME_SET,
                    "set_name": "Stress Outcomes",
                }
            )
            doc.append("options", {"value": "Pass", "label": "Pass", "is_pass": 1})
            doc.append(
                "options",
                {"value": "Fail", "label": "Fail", "is_pass": 0, "is_critical": 1},
            )
            doc.insert(ignore_permissions=True)

        if not frappe.db.exists("Process Definition", CODE):
            doc = frappe.get_doc(
                {
                    "doctype": "Process Definition",
                    "process_code": CODE,
                    "process_name": "Stress Sync Process",
                    "family": CODE,
                    "version": 1,
                    "status": "Published",
                    "pass_threshold_pct": 100,
                }
            )
            doc.append("stages", {"stage_code": "S1", "label": "Stage One", "sequence": 1})
            # A realistic inspection: 59 checks, matching Battery QC.
            for i in range(1, STEP_COUNT + 1):
                doc.append(
                    "steps",
                    {
                        "step_code": f"C{i}",
                        "stage": "S1",
                        "sequence": i,
                        "display_no": str(i),
                        "label": f"Check {i}",
                        "response_type": "Choice",
                        "outcome_set": OUTCOME_SET,
                        "is_mandatory": 1,
                        "is_active": 1,
                        "weight": 1,
                    },
                )
            doc.insert(ignore_permissions=True)
        frappe.db.commit()
        frappe.clear_cache()
    finally:
        frappe.destroy()


# ------------------------------------------------------------------ workers


def _sync(payload):
    """One sync call in its own process. Returns (ok, label, detail)."""
    frappe = None
    try:
        frappe = _connect()
        import json

        from vehicle_maintenance.api import process_sync

        out = process_sync.sync_run(json.dumps(payload))
        return (True, "ok", out["data"]["name"])
    except Exception as exc:  # noqa: BLE001 — the point is to catch everything
        return (False, type(exc).__name__, str(exc)[:200])
    finally:
        if frappe:
            try:
                frappe.destroy()
            except Exception:
                pass


def _attach(args):
    run, client_uuid = args
    frappe = None
    try:
        frappe = _connect()
        from vehicle_maintenance.api import process_sync

        out = process_sync.attach_photo_synced(
            run=run,
            step_code="C1",
            file_url="/private/files/stress.jpg",
            client_uuid=client_uuid,
        )
        return (True, "dup" if out["data"]["duplicate"] else "new", "")
    except Exception as exc:  # noqa: BLE001
        return (False, type(exc).__name__, str(exc)[:200])
    finally:
        if frappe:
            try:
                frappe.destroy()
            except Exception:
                pass


def batch(uuid, answers, submit=False, ident=None):
    return {
        "client_uuid": uuid,
        "process": CODE,
        "identifier": ident or f"PACK-{uuid[:8]}",
        "answers": answers,
        "submit_stages": ["S1"] if submit else [],
    }


def answers_for(count, start=1, response="Pass"):
    return [
        {"step_code": f"C{i}", "response": response, "client_seq": i}
        for i in range(start, start + count)
    ]


# ---------------------------------------------------------------- scenarios


def report(name, results, expect_all_ok=True):
    ok = sum(1 for r in results if r[0])
    bad = [r for r in results if not r[0]]
    status = "PASS" if (not bad or not expect_all_ok) else "FAIL"
    print(f"  [{status}] {name}: {ok}/{len(results)} succeeded")
    seen = {}
    for _, kind, detail in bad:
        seen.setdefault(kind, [0, detail])
        seen[kind][0] += 1
    for kind, (n, detail) in seen.items():
        print(f"        {n} x {kind}: {detail}")
    return not bad


def scenario_replay_storm(pool):
    """Thirty retries of one batch arriving together.

    The van-reaches-signal case, and the one that produced 29 errors out of 30
    against `start_run` before it owned its unique-key race.
    """
    import frappe

    uuid = f"stress-replay-{int(time.time())}"
    payload = batch(uuid, answers_for(10))
    results = pool.map(_sync, [payload] * 30)
    good = report("30 concurrent replays of one batch", results)

    f = _connect()
    try:
        runs = frappe.db.count("Process Run", {"client_uuid": uuid})
        name = frappe.db.get_value("Process Run", {"client_uuid": uuid}, "name")
        rows = frappe.db.count("Process Run Result", {"parent": name}) if name else 0
        print(f"        runs created: {runs} (want 1), result rows: {rows} (want 10)")
        good = good and runs == 1 and rows == 10
    finally:
        f.destroy()
    return good


def scenario_full_shift(pool, operators=20):
    """Twenty engineers each syncing a complete 59-check inspection at once."""
    payloads = [
        batch(f"stress-shift-{int(time.time())}-{i}", answers_for(STEP_COUNT), submit=True)
        for i in range(operators)
    ]
    started = time.time()
    results = pool.map(_sync, payloads)
    elapsed = time.time() - started
    good = report(f"{operators} full {STEP_COUNT}-check inspections, concurrent", results)
    total = operators * STEP_COUNT
    print(f"        {total} answers in {elapsed:.1f}s ({total / elapsed:.0f}/s)")
    return good


def scenario_same_run_hammer(pool):
    """One run, many concurrent batches — the retry storm on a single document.

    This is where InnoDB kills transactions: every batch rewrites the same run's
    child tables, so they contend for the same index gaps.
    """
    import frappe

    uuid = f"stress-hammer-{int(time.time())}"
    # Seed the run first so all 24 workers contend on an update, not a create.
    _sync(batch(uuid, answers_for(1)))
    # Every batch stays inside the 59 real steps — stepping past them made
    # the scenario measure "does apply_answer reject unknown codes", which
    # is already a unit test, instead of measuring contention.
    payloads = [batch(uuid, answers_for(5, start=1 + (i * 5) % 55)) for i in range(24)]
    results = pool.map(_sync, payloads)
    good = report("24 concurrent batches against ONE run", results)

    f = _connect()
    try:
        name = frappe.db.get_value("Process Run", {"client_uuid": uuid}, "name")
        rows = frappe.db.count("Process Run Result", {"parent": name})
        # 24 batches x 5 steps, wrapping over 55 codes — every distinct code
        # touched must be present exactly once.
        print(f"        result rows: {rows} (want 55)")
        good = good and rows == 55
    finally:
        f.destroy()
    return good


def scenario_photo_storm(pool):
    """The same photo attached twenty times — one upload whose response was lost."""
    import frappe

    uuid = f"stress-photo-{int(time.time())}"
    _sync(batch(uuid, answers_for(2)))
    f = _connect()
    try:
        run = frappe.db.get_value("Process Run", {"client_uuid": uuid}, "name")
    finally:
        f.destroy()

    photo_uuid = f"{uuid}-photo-1"
    results = pool.map(_attach, [(run, photo_uuid)] * 20)
    good = report("20 concurrent attaches of ONE photo", results)

    f = _connect()
    try:
        rows = frappe.db.count("Process Run Photo", {"parent": run})
        print(f"        photo rows: {rows} (want 1)")
        good = good and rows == 1
    finally:
        f.destroy()
    return good


def scenario_correction_race(pool):
    """A correction landing while the original is still in flight."""
    import frappe

    uuid = f"stress-correct-{int(time.time())}"
    _sync(batch(uuid, answers_for(3)))
    # Two batches disagreeing about C1, sent together. Either may win — what
    # must not happen is an error, a duplicate row, or a lost run.
    a = batch(uuid, [{"step_code": "C1", "response": "Fail", "client_seq": 10}])
    b = batch(uuid, [{"step_code": "C1", "response": "Pass", "client_seq": 11}])
    results = pool.map(_sync, [a, b] * 8)
    good = report("16 interleaved corrections of one answer", results)

    f = _connect()
    try:
        name = frappe.db.get_value("Process Run", {"client_uuid": uuid}, "name")
        rows = frappe.db.count("Process Run Result", {"parent": name, "step_code": "C1"})
        print(f"        C1 rows: {rows} (want 1)")
        good = good and rows == 1
    finally:
        f.destroy()
    return good


def scenario_submit_race(pool):
    """Eight simultaneous submits of one finished stage."""
    import frappe

    uuid = f"stress-submit-{int(time.time())}"
    _sync(batch(uuid, answers_for(STEP_COUNT)))
    payload = batch(uuid, [], submit=True)
    results = pool.map(_sync, [payload] * 8)
    good = report("8 concurrent submits of one stage", results)

    f = _connect()
    try:
        name = frappe.db.get_value("Process Run", {"client_uuid": uuid}, "name")
        signoffs = frappe.db.count(
            "Process Run Signoff", {"parent": name, "level": "Operator"}
        )
        status = frappe.db.get_value("Process Run", name, "status")
        print(f"        operator sign-offs: {signoffs} (want 1), status: {status}")
        good = good and signoffs == 1
    finally:
        f.destroy()
    return good


def main():
    print("Seeding the stress process…")
    ensure_definition()
    print(f"Definition {CODE} ready ({STEP_COUNT} checks)\n")

    workers = min(12, (os.cpu_count() or 4))
    print(f"Running with {workers} concurrent processes\n")
    ok = True
    with mp.Pool(workers) as pool:
        for fn in (
            scenario_replay_storm,
            scenario_same_run_hammer,
            scenario_correction_race,
            scenario_photo_storm,
            scenario_submit_race,
            scenario_full_shift,
        ):
            print(fn.__doc__.strip().splitlines()[0])
            try:
                ok = fn(pool) and ok
            except Exception:
                traceback.print_exc()
                ok = False
            print()

    print("=" * 60)
    print("ALL SCENARIOS PASSED" if ok else "SOME SCENARIOS FAILED — see above")
    return 0 if ok else 1


if __name__ == "__main__":
    mp.set_start_method("spawn")
    sys.exit(main())
