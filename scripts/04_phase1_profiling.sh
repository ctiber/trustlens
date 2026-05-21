#!/bin/bash
set -e
TESTBED=~/trustlens/testbed
FRAMEWORK=~/trustlens/framework
RESULTS=~/trustlens/results
mkdir -p "$RESULTS"
source "$FRAMEWORK/.venv/bin/activate"

HISTORY_FILES=()

for PROFILE in v1 v2 v2-mtls v2-vault v3; do
  echo "=== Collecting history: profile=$PROFILE ==="
  cd "$TESTBED"

  COMPOSE_FLAGS="-f docker-compose.yml"
  case "$PROFILE" in
    v1)       COMPOSE_FLAGS="$COMPOSE_FLAGS -f docker-compose.v1.yml" ;;
    v2-mtls)  COMPOSE_FLAGS="$COMPOSE_FLAGS -f docker-compose.v2-mtls.yml" ;;
    v2-vault) COMPOSE_FLAGS="$COMPOSE_FLAGS -f docker-compose.v2-vault.yml" ;;
    v3)       COMPOSE_FLAGS="$COMPOSE_FLAGS -f docker-compose.v3.yml" ;;
  esac

  docker compose $COMPOSE_FLAGS up -d --build --quiet-pull 2>/dev/null

  sleep 25

  OUTFILE="$RESULTS/history_${PROFILE}.csv"

  # Start exporter in background so it captures metrics while Gatling runs
  python3 "$FRAMEWORK/export_prometheus.py" \
    --window 60 --interval 5 --output "$OUTFILE" &
  EXPORTER_PID=$!

  (cd ~/trustlens/gatling && mvn -q gatling:test \
    -Dgatling.simulationClass=trustlens.HistorySimulation \
    -Dgatling.runDescription="history-$PROFILE") \
    2>/dev/null || true

  kill $EXPORTER_PID 2>/dev/null || true
  wait $EXPORTER_PID 2>/dev/null || true
  HISTORY_FILES+=("$OUTFILE")

  docker compose $COMPOSE_FLAGS down -v --remove-orphans 2>/dev/null
  sleep 10
done

# Merge
python3 -c "
import pandas as pd, sys
files = sys.argv[1:]
df = pd.concat([pd.read_csv(f) for f in files], ignore_index=True)
df.to_csv('$RESULTS/history_all.csv', index=False)
print(f'Merged: {len(df)} rows')
" "${HISTORY_FILES[@]}"

# Layer 1
python3 "$FRAMEWORK/layer1_profiling.py" \
  --data "$RESULTS/history_all.csv" \
  --rpt  "$RESULTS/rpt.json"

echo "=== Phase 1 complete. RPT: $RESULTS/rpt.json ==="
