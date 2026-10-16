#!/usr/bin/env python3
"""
07_analyse_zt.py — computes every number reported in the paper from the raw
outputs of the fine-grained ZT campaign (results_zt/).

  python3 07_analyse_zt.py [isolation|posture|phase1|phase2|rq3|all]

Writes results_zt/analysis.json and prints human-readable tables.
"""
from __future__ import annotations

import json
import re
import sys
from datetime import datetime
from pathlib import Path

import numpy as np
import pandas as pd
from scipy import stats

ROOT = Path.home() / "trustlens"
RES = ROOT / "results_zt"
sys.path.insert(0, str(ROOT / "framework"))

OUT = RES / "analysis.json"
ANALYSIS = json.loads(OUT.read_text()) if OUT.exists() else {}

SINGLE = {"p2": "v2", "p3": "iso-p3", "p4": "iso-p4", "p5": "iso-p5"}
POSTURE_VEC = {
    "v1": (1, 0, 0, 0, 0), "v2": (1, 1, 0, 0, 0), "v2-mtls": (1, 1, 1, 0, 0),
    "v2-mtls-sr": (1, 1, 1, 1, 0), "v3": (1, 1, 1, 1, 1),
}


def save():
    OUT.write_text(json.dumps(ANALYSIS, indent=2, default=float))


def cohen_d(a, b):
    a, b = np.asarray(a, float), np.asarray(b, float)
    sp = np.sqrt(((len(a) - 1) * a.var(ddof=1) + (len(b) - 1) * b.var(ddof=1))
                 / (len(a) + len(b) - 2))
    return (b.mean() - a.mean()) / sp if sp > 0 else float("nan")


# ── Gatling raw logs (sub-millisecond means; stats.json rounds to 1 ms) ─────

GATLING = ROOT / "results" / "gatling"
_RUN_INDEX = None


def gatling_dir(description: str) -> Path | None:
    global _RUN_INDEX
    if _RUN_INDEX is None:
        _RUN_INDEX = {}
        for d in sorted(GATLING.iterdir()):   # names embed the timestamp
            log = d / "simulation.log"
            if log.exists():
                with log.open() as fh:
                    parts = fh.readline().rstrip("\n").split("\t")
                if len(parts) > 4:
                    _RUN_INDEX[parts[4]] = d   # latest wins when names repeat
    return _RUN_INDEX.get(description)


def gatling_latency(description: str, request: str = "updateLocation") -> dict:
    d = gatling_dir(description)
    if d is None:
        return {}
    lat, ko, n = [], 0, 0
    with (d / "simulation.log").open() as fh:
        for line in fh:
            if not line.startswith("REQUEST"):
                continue
            f = line.split("\t")
            if request and f[2] != request:
                continue
            n += 1
            if f[5] != "OK":
                ko += 1
                continue
            lat.append(int(f[4]) - int(f[3]))
    a = np.asarray(lat, float)
    return {"mean": a.mean(), "p50": np.percentile(a, 50), "p95": np.percentile(a, 95),
            "p99": np.percentile(a, 99), "n": n, "ko_pct": 100 * ko / max(n, 1)}


# ── Isolation ────────────────────────────────────────────────────────────────

def isolation():
    df = pd.read_csv(RES / "isolation_results.csv")
    # Replace Gatling's 1-ms-rounded statistics by values from the raw logs
    df = df.astype({c: float for c in ["upd_mean_ms", "upd_p50_ms", "upd_p95_ms", "upd_p99_ms"]})
    for i, r in df.iterrows():
        g = gatling_latency(f"iso-{r.config}-run{r.run}")
        if g:
            df.loc[i, ["upd_mean_ms", "upd_p50_ms", "upd_p95_ms", "upd_p99_ms"]] = \
                [g["mean"], g["p50"], g["p95"], g["p99"]]
    metrics = {"T_avg_ms": "server T_avg", "upd_mean_ms": "e2e mean (update)",
               "upd_p50_ms": "e2e P50", "upd_p99_ms": "e2e P99",
               "U_res_pct": "U_res", "ra_call_ms": "RA call"}
    summary = df.groupby("config").agg(["mean", "std", "count"])
    base = df[df.config == "v1"]
    out = {"per_config": {}, "delta": {}}
    print("\n=== Isolation: per configuration (mean ± sd over runs) ===")
    print(f"{'config':12s} {'n':>2s} " + " ".join(f"{m:>18s}" for m in metrics))
    for cfg in ["v1", "v2", "iso-p3", "iso-p4", "iso-p5", "iso-p5-half", "v3", "iso-floor"]:
        if cfg not in summary.index:
            continue
        row = {m: (summary.loc[cfg, (m, "mean")], summary.loc[cfg, (m, "std")]) for m in metrics}
        out["per_config"][cfg] = {m: {"mean": v[0], "sd": v[1]} for m, v in row.items()}
        out["per_config"][cfg]["n"] = int(summary.loc[cfg, ("T_avg_ms", "count")])
        out["per_config"][cfg]["ko_pct"] = float(df[df.config == cfg].ko_pct.mean())
        print(f"{cfg:12s} {out['per_config'][cfg]['n']:2d} "
              + " ".join(f"{v[0]:9.3f} ±{v[1]:7.3f}" for v in row.values()))

    print("\n=== Marginal cost of each primitive in isolation (vs v1) ===")
    for pid, cfg in list(SINGLE.items()) + [("p5@0.5", "iso-p5-half"), ("V3", "v3"), ("floor", "iso-floor")]:
        grp = df[df.config == cfg]
        if grp.empty or base.empty:
            continue
        d = {}
        for m in ["T_avg_ms", "upd_mean_ms", "upd_p50_ms", "upd_p99_ms", "U_res_pct"]:
            delta = grp[m].mean() - base[m].mean()
            p = stats.ttest_ind(grp[m], base[m], equal_var=False).pvalue if len(grp) > 1 else float("nan")
            d[m] = {"delta": delta, "rel_pct": 100 * delta / base[m].mean(), "p": p,
                    "d": cohen_d(base[m], grp[m])}
        dp50 = d["upd_p50_ms"]["delta"]
        d["kappa"] = d["upd_p99_ms"]["delta"] / dp50 if abs(dp50) >= 0.5 else float("nan")
        out["delta"][pid] = d
        print(f"{pid:7s} T_avg {d['T_avg_ms']['delta']:+8.3f} ms ({d['T_avg_ms']['rel_pct']:+7.1f}%, "
              f"p={d['T_avg_ms']['p']:.4f}, d={d['T_avg_ms']['d']:.2f}) | e2e mean "
              f"{d['upd_mean_ms']['delta']:+7.2f} ms (p={d['upd_mean_ms']['p']:.4f}) | "
              f"P50 {dp50:+5.1f} P99 {d['upd_p99_ms']['delta']:+7.1f} κ={d['kappa']:.1f} | "
              f"U_res {d['U_res_pct']['delta']:+6.2f} pt")

    # Additivity: Σ ΔT_i of the single primitives vs measured ΔT(V3)
    add = {}
    for m in ["T_avg_ms", "upd_mean_ms"]:
        if all(p in out["delta"] for p in SINGLE) and "V3" in out["delta"]:
            s = sum(out["delta"][p][m]["delta"] for p in SINGLE)
            v3 = out["delta"]["V3"][m]["delta"]
            add[m] = {"sum_single": s, "v3": v3, "ratio": v3 / s if s else float("nan")}
            print(f"Additivity [{m}]: Σ ΔT_i = {s:.3f} ms, ΔT(V3) = {v3:.3f} ms, ratio = {v3 / s:.2f}")
    out["additivity"] = add
    if "p5" in out["delta"] and "p5@0.5" in out["delta"]:
        r = {m: out["delta"]["p5@0.5"][m]["delta"] / out["delta"]["p5"][m]["delta"]
             for m in ["T_avg_ms", "upd_mean_ms"]}
        out["linearity_p5"] = r
        print(f"Linearity of a5: ΔT(a5=0.5)/ΔT(a5=1) = {r}")
    ANALYSIS["isolation"] = out
    save()


# ── Posture histories (Security Tax per reference posture) ──────────────────

def posture():
    g = pd.read_csv(RES / "posture_gatling.csv").set_index("posture")
    out = {}
    print("\n=== Security Tax per reference posture (stepped load 100→1000 users/s) ===")
    g = g.astype(float)
    for p in g.index:
        gl = gatling_latency(f"history-{p}")
        if gl:
            g.loc[p, ["upd_mean_ms", "upd_p50_ms", "upd_p99_ms"]] = [gl["mean"], gl["p50"], gl["p99"]]
    base_e2e = g.loc["v1", "upd_mean_ms"]
    base_srv = None
    for p in POSTURE_VEC:
        f = RES / f"history_{p}.csv"
        if p not in g.index or not f.exists():
            continue
        h = pd.read_csv(f)
        h = h[h.T_avg > 0]
        srv = h.T_avg.mean() * 1000
        if p == "v1":
            base_srv = srv
        out[p] = {
            "vector": POSTURE_VEC[p], "server_T_ms": srv, "n_obs": len(h),
            "e2e_mean_ms": g.loc[p, "upd_mean_ms"], "e2e_p50_ms": g.loc[p, "upd_p50_ms"],
            "e2e_p99_ms": g.loc[p, "upd_p99_ms"], "ko_pct": g.loc[p, "ko_pct"],
            "R_sec_mean": h.R_sec.mean(), "U_res_mean": h.U_res.mean(),
        }
    for p, v in out.items():
        v["tax_e2e_pct"] = 100 * (v["e2e_mean_ms"] - base_e2e) / base_e2e
        v["tax_server_pct"] = 100 * (v["server_T_ms"] - base_srv) / base_srv
        print(f"{p:11s} {str(v['vector']):16s} server T={v['server_T_ms']:7.3f} ms "
              f"(tax {v['tax_server_pct']:+7.1f}%) | e2e mean={v['e2e_mean_ms']:6.1f} ms "
              f"(tax {v['tax_e2e_pct']:+7.1f}%) P50={v['e2e_p50_ms']} P99={v['e2e_p99_ms']} "
              f"KO={v['ko_pct']}% R_sec={v['R_sec_mean']:.3f} N={v['n_obs']}")
    ANALYSIS["posture"] = out
    save()


# ── Phase 1: RPT ─────────────────────────────────────────────────────────────

def phase1():
    from sklearn.metrics import silhouette_score
    from layer1_profiling import RegimeProfiler, load_csv
    D = load_csv(str(RES / "history_all.csv"))
    prof = RegimeProfiler()
    Dn = prof.fit_scaler(D)
    labels = prof.cluster(Dn)
    sil = silhouette_score(Dn, labels)
    rpt = json.loads((RES / "rpt.json").read_text())
    lab = pd.read_csv(RES / "history_all_labelled.csv")
    lab["regime"] = labels
    out = {"N": int(len(D)), "silhouette": float(sil), "regimes": []}
    print(f"\n=== Phase 1: N={len(D)}  K={len(set(labels))}  silhouette={sil:.3f} ===")
    for r in rpt["regimes"]:
        k = r["regime_id"]
        members = lab[lab.regime == k]
        comp = members.source.value_counts().to_dict()
        s = [r["recommended_config"][p] for p in ["p1", "p2", "p3", "p4", "p5"]]
        reg = {
            "id": k, "N": int(len(members)), "T_ms": members.T_avg.mean() * 1000,
            "U_res": members.U_res.mean(), "F_rate": members.F_rate.mean(),
            "R_sec": members.R_sec.mean(), "s_star": s, "phi": r["score"],
            "R_sec_norm": r["feature_ranges"]["R_sec"]["mean"],
            "F_rate_norm": r["feature_ranges"]["F_rate"]["mean"], "composition": comp,
        }
        out["regimes"].append(reg)
        print(f"R{k}: N={reg['N']:4d} T={reg['T_ms']:8.2f} ms F={reg['F_rate']:.3f} "
              f"R_sec={100 * reg['R_sec']:5.2f}% U={reg['U_res']:.2f} s*={s} Φ={reg['phi']:.4f} {comp}")
    out["R_sec_max"] = float(lab.R_sec.max())
    ANALYSIS["phase1"] = out
    save()


# ── Phase 2: drift detection ─────────────────────────────────────────────────

TICK_RE = re.compile(r"^(\S+ \S+),\d+ \[INFO\] Tick (\d+) \| R(\S+) \| d_M=([\d.]+) \| S=([\d.]+) \| uncharted=(\w+)")


def load_ticks():
    rows = []
    for line in (RES / "phase2_monitor.log").read_text().splitlines():
        m = TICK_RE.match(line)
        if m:
            ts = datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S").timestamp()
            rows.append({"epoch": ts, "tick": int(m.group(2)), "regime": m.group(3),
                         "d": float(m.group(4)), "S": float(m.group(5)),
                         "uncharted": m.group(6) == "True"})
    return pd.DataFrame(rows)


def windows():
    tl = pd.read_csv(RES / "phase2_timeline.csv").set_index("event").epoch.to_dict()
    # Attack windows as scheduled inside the Gatling simulations
    return tl, {
        "A (lateral movement)": (tl["A_start"] + 180, tl["A_start"] + 360),
        "B (Redis outage)": (tl["redis_stop"], tl["redis_start"]),
        "B (credential replay)": (tl["B_start"] + 360, tl["B_start"] + 600),
        "C (business logic)": (tl["C_start"], tl["C_start"] + 120),
    }


def phase2():
    ticks = load_ticks()
    alerts = pd.read_json(RES / "alerts.jsonl", lines=True, convert_dates=False)
    tl, win = windows()
    feats = pd.read_csv(RES / "phase2_features.csv")
    alerts["epoch"] = alerts.timestamp
    GRACE = 60   # 60 s PromQL rate window: signals persist one window after the end
    out = {"ticks": int(len(ticks)), "alerts": int(len(alerts)), "episodes": {}}
    print(f"\n=== Phase 2: {len(ticks)} ticks, {len(alerts)} alerts ===")
    in_any = np.zeros(len(alerts), bool)
    tick_in = np.zeros(len(ticks), bool)
    # stack restarts between scenarios: Prometheus is recreated, exclude ±60 s
    restarts = [(tl["A_end"] + 30, tl["B_start"] + 60), (tl["B_end"] + 30, tl["C_start"] + 60)]
    for name, (a, b) in win.items():
        sel = (alerts.epoch >= a) & (alerts.epoch <= b + GRACE)
        in_any |= sel.values
        tick_in |= ((ticks.epoch >= a) & (ticks.epoch <= b + GRACE)).values
        first = alerts[sel].epoch.min() if sel.any() else float("nan")
        f = feats[(feats.timestamp >= a) & (feats.timestamp <= b + GRACE)]
        ep = {"start": a, "end": b, "alerts": int(sel.sum()),
              "latency_s": first - a if sel.any() else None,
              "peak_R_sec": float(f.R_sec.max()) if len(f) else None,
              "peak_F_rate": float(f.F_rate.max()) if len(f) else None,
              "regimes": alerts[sel].assigned_regime.value_counts().to_dict(),
              "s_star": alerts[sel].recommended_config.astype(str).value_counts().head(3).to_dict()}
        target = {"2", "3"} if "outage" in name else {"1"}
        hit = ticks[(ticks.epoch >= a) & (ticks.epoch <= b + GRACE) & ticks.regime.isin(target)]
        ep["assignment_latency_s"] = float(hit.epoch.min() - a) if len(hit) else None
        ep["assigned_ticks"] = int(len(hit))
        ep["window_ticks"] = int(((ticks.epoch >= a) & (ticks.epoch <= b)).sum())
        out["episodes"][name] = ep
        print(f"   context re-assignment: first tick after {ep['assignment_latency_s']} s, "
              f"{ep['assigned_ticks']} ticks in target context(s) {sorted(target)}")
        print(f"{name:24s} alerts={ep['alerts']:3d} latency={ep['latency_s']} s "
              f"peak R_sec={ep['peak_R_sec']} peak F={ep['peak_F_rate']} regimes={ep['regimes']}")
    for a, b in restarts:
        in_any |= ((alerts.epoch >= a) & (alerts.epoch <= b)).values
        tick_in |= ((ticks.epoch >= a) & (ticks.epoch <= b)).values
    quiet_alerts = alerts[~in_any]
    n_quiet_ticks = int((~tick_in).sum())
    out["false_positives"] = int(len(quiet_alerts))
    out["quiet_ticks"] = n_quiet_ticks
    out["fp_rate"] = len(quiet_alerts) / max(n_quiet_ticks, 1)
    out["fp_details"] = quiet_alerts[["tick", "assigned_regime", "is_uncharted",
                                      "feature_vector"]].to_dict("records")
    out["regime_share"] = ticks.regime.value_counts(normalize=True).to_dict()
    print(f"False positives: {len(quiet_alerts)} alerts over {n_quiet_ticks} quiescent ticks "
          f"(rate {out['fp_rate']:.3%})")
    print("Tick share per assigned regime:", out["regime_share"])
    ANALYSIS["phase2"] = out
    save()


# ── RQ3: tax under TrustLens vs static postures ─────────────────────────────

def rq3():
    rpt = json.loads((RES / "rpt.json").read_text())
    iso = ANALYSIS["isolation"]["per_config"]
    measured = {(1, 0, 0, 0, 0): "v1", (1, 1, 0, 0, 0): "v2", (1, 0, 1, 0, 0): "iso-p3",
                (1, 0, 0, 1, 0): "iso-p4", (1, 0, 0, 0, 1): "iso-p5", (1, 1, 1, 1, 1): "v3"}
    out = {}
    for metric in ["T_avg_ms", "upd_mean_ms"]:
        def lat(vec):
            key = tuple(int(round(a)) if a in (0, 1) else a for a in vec)
            if key in measured:
                return iso[measured[key]][metric]["mean"]
            dT = {p: max(ANALYSIS["isolation"]["delta"][p][metric]["delta"], 0.0)
                  for p in SINGLE}   # additive estimate for unmeasured postures
            return iso["v1"][metric]["mean"] + sum(a * dT[p] for a, p in zip(vec[1:], SINGLE))
        s_star = {str(r["regime_id"]): [r["recommended_config"][p] for p in ["p1", "p2", "p3", "p4", "p5"]]
                  for r in rpt["regimes"]}
        ticks = load_ticks()
        ticks = ticks[ticks.regime.isin(s_star)]
        t_tl = ticks.regime.map(lambda k: lat(s_star[k])).mean()
        t_v1, t_v2, t_v3 = lat((1, 0, 0, 0, 0)), lat((1, 1, 0, 0, 0)), lat((1, 1, 1, 1, 1))
        out[metric] = {
            "latency": {"V1": t_v1, "V2": t_v2, "V3": t_v3, "TrustLens": t_tl},
            "tax_pct": {"V2": 100 * (t_v2 - t_v1) / t_v1, "V3": 100 * (t_v3 - t_v1) / t_v1,
                        "TrustLens": 100 * (t_tl - t_v1) / t_v1},
            "reduction_vs_v3_pct": 100 * (1 - (t_tl - t_v1) / (t_v3 - t_v1)),
            "tick_posture_share": ticks.regime.map(lambda k: str(s_star[k])).value_counts(normalize=True).to_dict(),
        }
    print("\n=== RQ3: measured latency of the recommended posture at each Phase 2 tick ===")
    print(json.dumps(out, indent=2, default=float))
    ANALYSIS["rq3"] = out
    save()


# ── Closed-loop Phase 2 ──────────────────────────────────────────────────────

CL_WINDOWS = {"C1": (240, 360, "C1-violation"), "B": (540, 720, "B-replay"),
              "C2": (840, 960, "C2-violation")}


def closed_loop():
    base = RES / "closed_loop"
    runs = []
    for d in sorted(base.glob("*_run*")):
        if not (d / "done").exists():
            continue
        mode = d.name.split("_run")[0]
        gdir = Path((d / "gatling_dir").read_text().strip())
        log = gdir / "simulation.log"
        with log.open() as fh:
            t_start = int(fh.readline().split("\t")[3]) / 1000.0
        counts, legit_lat, legit_ko, legit_ko_outage = {}, [], 0, 0
        tl = pd.read_csv(d / "timeline.csv", header=None, index_col=0)[1].to_dict()
        r0, r1 = tl.get("redis_stop", 0), tl.get("redis_start", 0) + 90
        with log.open() as fh:
            for line in fh:
                if not line.startswith("REQUEST"):
                    continue
                f = line.split("\t")
                name, st, en, ok = f[2], int(f[3]) / 1000, int(f[4]) / 1000, f[5] == "OK"
                c = counts.setdefault(name, [0, 0]); c[0 if ok else 1] += 1
                if name == "updateLocation":
                    if ok and not (r0 <= st <= r1):
                        legit_lat.append((en - st) * 1000)
                    elif r0 <= st <= r1:
                        legit_ko_outage += 1
                    else:
                        legit_ko += 1
        alerts = pd.read_json(d / "alerts.jsonl", lines=True, convert_dates=False) \
            if (d / "alerts.jsonl").stat().st_size else pd.DataFrame(columns=["timestamp"])
        feats = pd.read_csv(d / "features.csv")
        feats = feats[(feats.timestamp >= t_start + 100) & (feats.timestamp <= t_start + 1500)
                      & ~((feats.timestamp >= r0) & (feats.timestamp <= r1))]   # outage excluded
        run = {"mode": mode, "run": d.name, "T_avg_ms": 1000 * feats.T_avg.mean(),
               "e2e_mean_ms": float(np.mean(legit_lat)), "e2e_p99_ms": float(np.percentile(legit_lat, 99)),
               "legit_ko_outside_outage": legit_ko, "legit_ko_outage": legit_ko_outage,
               "alerts": int(len(alerts))}
        posture = None
        if (d / "posture_log.csv").exists():
            posture = pd.read_csv(d / "posture_log.csv")
            applied = posture[posture.event.isin(["initial", "applied"])].copy()
            applied["full"] = applied.posture.str.contains(r"1.0, 1.0, 1.0, 1.0, 1.0")
            # fraction of the run spent under the full posture
            ts = list(applied.epoch) + [t_start + 1500]
            dur = sum(max(0, min(ts[i + 1], t_start + 1500) - max(ts[i], t_start)) * applied.full.iloc[i]
                      for i in range(len(applied)))
            run["full_posture_share"] = dur / 1500
            run["switches"] = int((posture.event == "applied").sum())
        for w, (a0, a1, req) in CL_WINDOWS.items():
            ok, ko = counts.get(req, [0, 0])
            onset = t_start + a0
            wa = alerts[(alerts.timestamp >= onset) & (alerts.timestamp <= t_start + a1 + 60)] if len(alerts) else alerts
            run[f"{w}_accepted"] = ok
            run[f"{w}_total"] = ok + ko
            run[f"{w}_accept_pct"] = 100 * ok / max(ok + ko, 1)
            run[f"{w}_alert_s"] = float(wa.timestamp.min() - onset) if len(wa) else None
            if posture is not None:
                esc = posture[(posture.event == "applied") & (posture.epoch >= onset)
                              & posture.posture.str.contains(r"1.0, 1.0, 1.0, 1.0, 1.0")]
                run[f"{w}_escalated_s"] = float(esc.epoch.min() - onset) if len(esc) and esc.epoch.min() <= t_start + a1 else None
        runs.append(run)
    df = pd.DataFrame(runs)
    pd.set_option("display.width", 250)
    print("\n=== Closed-loop Phase 2: per run ===")
    print(df.drop(columns=["run"]).round(2).to_string(index=False))
    agg = df.drop(columns=["run"]).groupby("mode").agg(["mean", "std"]).round(3)
    print("\n=== per mode (mean, sd) ===")
    print(agg.T.to_string())
    ANALYSIS["closed_loop"] = {"runs": runs,
                               "per_mode": json.loads(df.drop(columns=["run"]).groupby("mode").mean().to_json())}
    save()


if __name__ == "__main__":
    what = sys.argv[1] if len(sys.argv) > 1 else "all"
    steps = {"isolation": isolation, "posture": posture, "phase1": phase1,
             "phase2": phase2, "rq3": rq3, "closed_loop": closed_loop}
    for name, fn in steps.items():
        if what in (name, "all"):
            try:
                fn()
            except FileNotFoundError as ex:
                print(f"[{name}] skipped: {ex}")
