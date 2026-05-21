#!/bin/bash
set -e
source ~/trustlens/framework/.venv/bin/activate

python3 << 'PYEOF'
import pandas as pd
import numpy as np
from pathlib import Path

RESULTS = Path.home() / "trustlens/results"

def load_runs(profile, n=5):
    frames = []
    for i in range(1, n+1):
        f = RESULTS / f"baseline_{profile}_run{i}.csv"
        if f.exists():
            df = pd.read_csv(f); df["run"] = i; df["profile"] = profile
            frames.append(df)
    return pd.concat(frames, ignore_index=True) if frames else pd.DataFrame()

v1 = load_runs("V1"); v2 = load_runs("V2"); v3 = load_runs("V3")

print("\n=== Mean T_avg per baseline ===")
for name, df in [("V1",v1),("V2",v2),("V3",v3)]:
    if not df.empty:
        print(f"  Static-{name}: {df['T_avg'].mean()*1000:.1f} ms "
              f"(±{df['T_avg'].std()*1000:.1f})")

if not v1.empty:
    print("\n=== Security Tax vs Static-V1 ===")
    base = v1["T_avg"].mean()
    for name, df in [("V2",v2),("V3",v3)]:
        if not df.empty:
            tax = (df["T_avg"].mean() - base) / base * 100
            print(f"  Static-{name}: +{tax:.1f}%")

alerts = RESULTS / "alerts.jsonl"
if alerts.exists():
    a = pd.read_json(alerts, lines=True)
    print(f"\n=== Drift Alerts: {len(a)} total ===")
    if not a.empty:
        print(a[["tick","assigned_regime","cusum_statistic"]].to_string(index=False))

print("\n=== RPT summary ===")
import json
rpt_path = RESULTS / "rpt.json"
if rpt_path.exists():
    rpt = json.loads(rpt_path.read_text())
    for r in rpt["regimes"]:
        print(f"  R{r['regime_id']}  s*={list(r['recommended_config'].values())}  Φ={r['score']:.4f}")
PYEOF

echo "=== Analysis complete ==="
