#!/bin/bash
# Phase 1 — history collection (7 scenarios) and Layer 1 profiling, on the
# fine-grained testbed. Same scenario matrix as 04e_phase1_full_concurrent.sh;
# the exporter runs concurrently with Gatling.
set -e
source "$(cd "$(dirname "$0")" && pwd)/scripts_zt_lib.sh"
source "$FRAMEWORK/.venv/bin/activate"
COMPOSE="docker compose -f $TESTBED/docker-compose.zt.yml -p testbed"

run_scenario() {
  local PROFILE=$1 SIMULATION=$2 LABEL=$3 DURATION=$4 WITH_FAULT=${5:-}
  local OUTFILE="$RESULTS/history_${LABEL}.csv"
  if [ -s "$OUTFILE" ]; then echo "skip $LABEL (done)"; return; fi
  echo "=== [$(date +%T)] [$LABEL] posture=$PROFILE simulation=$SIMULATION ==="
  variant_up "$PROFILE"
  run_gatling WarmupSimulation "warmup-$LABEL" >/dev/null

  local TOTAL_SAMPLES=$(( (DURATION + 60) / 5 ))
  python3 "$FRAMEWORK/export_prometheus.py" --window 60 --samples "$TOTAL_SAMPLES" \
    --interval 5 --output "$OUTFILE.tmp" &
  local EXP=$!

  local FAULT=""
  if [ "$WITH_FAULT" = "fault" ]; then
    ( sleep 120; echo "  [fault] stopping Redis for 120 s"; $COMPOSE stop redis >/dev/null 2>&1
      sleep 120; $COMPOSE start redis >/dev/null 2>&1; echo "  [fault] Redis restarted" ) &
    FAULT=$!
  fi
  run_gatling "$SIMULATION" "$LABEL" >/dev/null
  [ -n "$FAULT" ] && wait $FAULT 2>/dev/null || true
  wait $EXP 2>/dev/null || true
  mv "$OUTFILE.tmp" "$OUTFILE"
  echo "  → $OUTFILE ($(wc -l < "$OUTFILE") rows)"
  variant_down
}

run_scenario v2 LowLoadSimulation     low_v2          600
run_scenario v2 PeakLoadSimulation    peak_v2         600
run_scenario v3 PeakLoadSimulation    peak_v3         600
run_scenario v2 BurstyLoadSimulation  bursty_v2       600
run_scenario v2 MixedAttackSimulation mixed_attack_v2 600
run_scenario v3 MixedAttackSimulation mixed_attack_v3 600
run_scenario v3 DegradedSimulation    degraded_v3     600 fault

echo "=== Merging history files ==="
python3 - "$RESULTS" <<'PY'
import pandas as pd, sys, pathlib
res = pathlib.Path(sys.argv[1])
labels = ["low_v2","peak_v2","peak_v3","bursty_v2","mixed_attack_v2","mixed_attack_v3","degraded_v3"]
dfs = []
for l in labels:
    df = pd.read_csv(res / f"history_{l}.csv"); df["source"] = l; dfs.append(df)
    print(f"  {l}: {len(df)} rows")
m = pd.concat(dfs, ignore_index=True)
m.to_csv(res / "history_all_labelled.csv", index=False)
m.drop(columns=["source"]).to_csv(res / "history_all.csv", index=False)
print(f"  total {len(m)} rows")
PY
echo "=== Layer 1 profiling ==="
(cd "$FRAMEWORK" && python3 layer1_profiling.py --data "$RESULTS/history_all.csv" --rpt "$RESULTS/rpt.json")
