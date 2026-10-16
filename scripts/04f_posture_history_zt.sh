#!/bin/bash
# Security Tax per reference posture (RQ1/RQ3): the cumulative postures of
# Table "mitigation" are each run under the stepped HistorySimulation
# (100→1000 users/s, 10×60 s, 10% invalid-JWT traffic) while the feature
# exporter polls Prometheus every 5 s.
#   v1 (1,0,0,0,0)  v2 (1,1,0,0,0)  v2-mtls (1,1,1,0,0)
#   v2-mtls-sr (1,1,1,1,0)  v3 (1,1,1,1,1)
# Output: results_zt/history_<posture>.csv + results_zt/posture_gatling.csv
set -e
source "$(cd "$(dirname "$0")" && pwd)/scripts_zt_lib.sh"
source "$FRAMEWORK/.venv/bin/activate"
POSTURES=${POSTURES:-"v1 v2 v2-mtls v2-mtls-sr v3"}
GOUT=$RESULTS/posture_gatling.csv
[ -f "$GOUT" ] || echo "posture,mean_ms,p50_ms,p95_ms,p99_ms,upd_mean_ms,upd_p50_ms,upd_p99_ms,ko_pct,n_req" > "$GOUT"

for P in $POSTURES; do
  OUTFILE="$RESULTS/history_${P}.csv"
  if [ -s "$OUTFILE" ] && grep -q "^$P," "$GOUT"; then echo "skip $P (done)"; continue; fi
  echo "=== [$(date +%T)] posture history: $P ==="
  variant_up "$P"
  run_gatling WarmupSimulation "warmup-hist-$P" >/dev/null
  python3 "$FRAMEWORK/export_prometheus.py" --window 60 --interval 5 --output "$OUTFILE" &
  EXP=$!
  REPORT=$(run_gatling HistorySimulation "history-$P")
  kill $EXP 2>/dev/null || true; wait $EXP 2>/dev/null || true
  python3 - "$REPORT" "$P" >> "$GOUT" <<'PY'
import json, sys, pathlib
s = json.loads((pathlib.Path(sys.argv[1]) / "js" / "stats.json").read_text())
g = s["stats"]
upd = next(c["stats"] for c in s["contents"].values() if c["name"] == "updateLocation")
n = g["numberOfRequests"]["total"]; ko = g["numberOfRequests"]["ko"]
row = [sys.argv[2], g["meanResponseTime"]["total"], g["percentiles1"]["total"],
       g["percentiles3"]["total"], g["percentiles4"]["total"],
       upd["meanResponseTime"]["total"], upd["percentiles1"]["total"], upd["percentiles4"]["total"],
       round(100.0 * ko / max(n, 1), 3), n]
print(",".join(map(str, row)))
PY
  tail -1 "$GOUT"
  variant_down
done
echo "=== Posture histories complete ==="
