#!/bin/bash
set -e
TESTBED=~/trustlens/testbed
FRAMEWORK=~/trustlens/framework
RESULTS=~/trustlens/results
source "$FRAMEWORK/.venv/bin/activate"

run_baseline() {
  local PROFILE=$1
  local RUN=$2
  echo "--- Static-$PROFILE run $RUN ---"
  cd "$TESTBED"
  if [ "$PROFILE" = "V1" ]; then
    docker compose -f docker-compose.yml -f docker-compose.v1.yml \
      up -d --build --quiet-pull 2>/dev/null
  elif [ "$PROFILE" = "V3" ]; then
    docker compose -f docker-compose.yml -f docker-compose.v3.yml \
      up -d --build --quiet-pull 2>/dev/null
  else
    docker compose up -d --build --quiet-pull 2>/dev/null
  fi
  sleep 25

  python3 "$FRAMEWORK/export_prometheus.py" \
    --window 60 --interval 5 \
    --output "$RESULTS/baseline_${PROFILE}_run${RUN}.csv" &
  EXPORTER_PID=$!

  (cd ~/trustlens/gatling && mvn -q gatling:test \
    -Dgatling.simulationClass=trustlens.FullExperimentSimulation \
    -Dgatling.runDescription="baseline-${PROFILE}-run${RUN}") \
    2>/dev/null || true

  kill $EXPORTER_PID 2>/dev/null || true
  wait $EXPORTER_PID 2>/dev/null || true
  docker compose down -v --remove-orphans 2>/dev/null
  sleep 10
}

for PROFILE in V1 V2 V3; do
  for RUN in 1 2 3 4 5; do
    run_baseline "$PROFILE" "$RUN"
  done
done
echo "=== Baselines complete ==="
