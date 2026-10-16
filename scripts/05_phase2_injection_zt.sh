#!/bin/bash
# Phase 2 — the three attack scenarios injected against the fine-grained V3
# posture, with Layer 2 monitoring continuously (same protocol as
# 05_phase2_injection.sh). Scenario B includes a 120 s Redis outage.
set -e
source "$(cd "$(dirname "$0")" && pwd)/scripts_zt_lib.sh"
source "$FRAMEWORK/.venv/bin/activate"
COMPOSE="docker compose -f $TESTBED/docker-compose.zt.yml -p testbed"
TL=$RESULTS/phase2_timeline.csv
echo "event,epoch" > "$TL"
mark() { echo "$1,$(date +%s)" >> "$TL"; echo "  [$(date +%T)] $1"; }

variant_up v3
rm -f "$RESULTS/alerts.jsonl"; touch "$RESULTS/alerts.jsonl"
(cd "$FRAMEWORK" && exec python3 layer2_monitoring.py --rpt "$RESULTS/rpt.json" \
   --alert "$RESULTS/alerts.jsonl" --warmup 20) > "$RESULTS/phase2_monitor.log" 2>&1 &
MON=$!
python3 "$FRAMEWORK/export_prometheus.py" --window 60 --interval 5 \
  --output "$RESULTS/phase2_features.csv" > /dev/null 2>&1 &
EXP=$!
mark monitor_start
sleep 120

mark A_start
run_gatling ScenarioASimulation scenario-A >/dev/null
mark A_end
sleep 30

variant_down; variant_up v3
mark B_start
( sleep 120; mark redis_stop; $COMPOSE stop redis >/dev/null 2>&1
  sleep 120; $COMPOSE start redis >/dev/null 2>&1; mark redis_start ) &
F=$!
run_gatling ScenarioBSimulation scenario-B >/dev/null
wait $F 2>/dev/null || true
mark B_end
sleep 30

variant_down; variant_up v3
mark C_start
run_gatling ScenarioCSimulation scenario-C >/dev/null
mark C_end
sleep 30

kill $MON $EXP 2>/dev/null || true
mark monitor_end
variant_down
echo "=== Phase 2 complete. Alerts: $RESULTS/alerts.jsonl ==="
