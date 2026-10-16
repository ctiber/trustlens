#!/bin/bash
# Closed-loop Phase 2 — TrustLens recommendations applied at runtime.
# One continuous 25-min run per repetition (ClosedLoopSimulation): C1, B,
# C2 attacks and a Redis outage, while Layer 2 monitors and
# closed_loop_controller.py applies the RPT posture of the alerted context
# (operator delay 30 s; downgrade after 24 benign ticks + 30 s).
# Baselines use the same infrastructure with a fixed posture:
#   static-light  s=(1,0,0,0,0) + observation floor (TrustLens posture when benign)
#   static-v3     s=(1,1,1,1,1)
# Output: results_zt/closed_loop/<mode>_run<N>/
set -e
source "$(cd "$(dirname "$0")" && pwd)/scripts_zt_lib.sh"
source "$FRAMEWORK/.venv/bin/activate"
COMPOSE="docker compose -f $TESTBED/docker-compose.zt.yml -p testbed"
ORDER=${ORDER:-"cl:1 light:1 cl:2 v3:1 cl:3 light:2 cl:4 v3:2 cl:5"}

for ITEM in $ORDER; do
  MODE=${ITEM%%:*}; RUN=${ITEM##*:}
  OUT=$RESULTS/closed_loop/${MODE}_run${RUN}
  if [ -f "$OUT/done" ]; then echo "skip $MODE run $RUN"; continue; fi
  rm -rf "$OUT"; mkdir -p "$OUT"
  VARIANT=cl-light; [ "$MODE" = v3 ] && VARIANT=cl-v3
  echo "=== [$(date +%T)] closed-loop experiment mode=$MODE run=$RUN ==="
  variant_up "$VARIANT"
  run_gatling WarmupSimulation "warmup-cl-$MODE-$RUN" >/dev/null
  sleep 60   # let the 60 s feature window forget the warm-up ramp

  T0=$(date +%s); echo "t0,$T0" > "$OUT/timeline.csv"
  (cd "$FRAMEWORK" && exec python3 layer2_monitoring.py --rpt "$RESULTS/rpt.json" \
     --alert "$OUT/alerts.jsonl" --warmup 20) > "$OUT/monitor.log" 2>&1 &
  MON=$!
  python3 "$FRAMEWORK/export_prometheus.py" --window 60 --interval 5 \
     --output "$OUT/features.csv" > /dev/null 2>&1 &
  EXP=$!
  CTL=""
  if [ "$MODE" = cl ]; then
    touch "$OUT/alerts.jsonl"
    python3 "$FRAMEWORK/closed_loop_controller.py" --rpt "$RESULTS/rpt.json" \
      --monitor-log "$OUT/monitor.log" --alerts "$OUT/alerts.jsonl" \
      --out "$OUT/posture_log.csv" --initial 1,0,0,0,0 > "$OUT/controller.log" 2>&1 &
    CTL=$!
  fi
  ( sleep 1200; echo "redis_stop,$(date +%s)" >> "$OUT/timeline.csv"; $COMPOSE stop redis >/dev/null 2>&1
    sleep 120; $COMPOSE start redis >/dev/null 2>&1; echo "redis_start,$(date +%s)" >> "$OUT/timeline.csv" ) &
  FAULT=$!
  REPORT=$(run_gatling ClosedLoopSimulation "cl-$MODE-run$RUN")
  echo "gatling_end,$(date +%s)" >> "$OUT/timeline.csv"
  wait $FAULT 2>/dev/null || true
  sleep 20
  kill $MON $EXP $CTL 2>/dev/null || true
  echo "$REPORT" > "$OUT/gatling_dir"
  touch "$OUT/done"
  variant_down
done
echo "=== Closed-loop experiments complete ==="
