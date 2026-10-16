#!/bin/bash
# Primitive cost isolation with fine-grained, independently switchable primitives.
#
# True one-at-a-time isolation (ZT-Tax protocol): each primitive is enabled
# ALONE on top of the p1 baseline, plus the full V3 posture to test additivity
# and p5 at a5=0.5 to test the linearity of the graduated activation level.
#   v1          s=(1,0,0,0,0)     baseline
#   v2          s=(1,1,0,0,0)     p2 alone (JWT at every service)
#   iso-p3      s=(1,0,1,0,0)     p3 alone (mTLS HTTP + Kafka)
#   iso-p4      s=(1,0,0,1,0)     p4 alone (Vault rotation, T_rot = 60 s)
#   iso-p5      s=(1,0,0,0,1)     p5 alone (RA on every request)
#   iso-p5-half s=(1,0,0,0,0.5)   p5 at a5 = 0.5 (RA on 50% of requests)
#   v3          s=(1,1,1,1,1)     all primitives
# Load: 30 s warm-up (WarmupSimulation, discarded), then LocationUpdateSimulation,
# 500 users/s (login + update) for 120 s.
# Runs are interleaved across configurations (round-robin) to spread thermal
# and background drift evenly. Output: results_zt/isolation_results.csv
set -e
source "$(cd "$(dirname "$0")" && pwd)/scripts_zt_lib.sh"
RUNS=${RUNS:-5}
CONFIGS=${CONFIGS:-"v1 v2 iso-p3 iso-p4 iso-p5 iso-p5-half v3"}
OUT=${OUT:-$RESULTS/isolation_results.csv}

[ -f "$OUT" ] || echo "config,run,T_avg_ms,U_res_pct,F_rate,R_sec,ra_call_ms,mean_ms,p50_ms,p95_ms,p99_ms,upd_mean_ms,upd_p50_ms,upd_p95_ms,upd_p99_ms,ko_pct,n_req" > "$OUT"

for RUN in $(seq 1 "$RUNS"); do
  for CFG in $CONFIGS; do
    if grep -q "^$CFG,$RUN," "$OUT"; then echo "skip $CFG run $RUN (done)"; continue; fi
    echo "=== [$(date +%T)] isolation $CFG run $RUN ==="
    variant_up "$CFG"
    run_gatling WarmupSimulation "warmup-$CFG-run$RUN" >/dev/null
    sleep 5
    START=$(date +%s)
    REPORT=$(run_gatling LocationUpdateSimulation "iso-$CFG-run$RUN")
    END=$(date +%s); W=$(( END - START ))
    sleep 6   # one more scrape so that the window covers the whole load

    T_AVG=$(prom "sum(rate(http_server_requests_seconds_sum{uri!~\"/actuator.*\"}[${W}s]))/sum(rate(http_server_requests_seconds_count{uri!~\"/actuator.*\"}[${W}s]))*1000" "$END")
    U_RES=$(prom "avg(avg_over_time(process_cpu_usage[${W}s]))*100" "$END")
    F_RATE=$(prom "(sum(rate(http_server_requests_seconds_count{status=~\"5..\",uri!~\"/actuator.*\"}[${W}s])) or vector(0))/sum(rate(http_server_requests_seconds_count{uri!~\"/actuator.*\"}[${W}s]))" "$END")
    R_SEC=$(prom "(sum(rate(http_server_requests_seconds_count{status=~\"4..\",uri!~\"/actuator.*\"}[${W}s])) or vector(0))/sum(rate(http_server_requests_seconds_count{uri!~\"/actuator.*\"}[${W}s]))" "$END")
    RA_MS=$(prom "sum(rate(zt_ra_call_seconds_sum[${W}s]))/sum(rate(zt_ra_call_seconds_count[${W}s]))*1000" "$END")
    [[ "$RA_MS" == "NaN" ]] && RA_MS=0

    GSTATS=$(python3 - "$REPORT" <<'PY'
import json, sys, pathlib
d = pathlib.Path(sys.argv[1]) / "js" / "stats.json"
s = json.loads(d.read_text())
def pct(st): return [st["meanResponseTime"]["total"], st["percentiles1"]["total"],
                     st["percentiles3"]["total"], st["percentiles4"]["total"]]
g = s["stats"]
upd = next(c["stats"] for c in s["contents"].values() if c["name"] == "updateLocation")
n = g["numberOfRequests"]["total"]; ko = g["numberOfRequests"]["ko"]
print(",".join(str(x) for x in pct(g) + pct(upd) + [round(100.0 * ko / max(n, 1), 3), n]))
PY
)
    echo "$CFG,$RUN,$T_AVG,$U_RES,$F_RATE,$R_SEC,$RA_MS,$GSTATS" | tee -a "$OUT"
    variant_down
  done
done
echo "=== Isolation complete → $OUT ==="
