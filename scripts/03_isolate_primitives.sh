#!/bin/bash
# Measures the marginal cost of each primitive in isolation.
# Results are written to ~/trustlens/results/isolation_results.csv
set -e

TESTBED=~/trustlens/testbed
OUTPUT=~/trustlens/results/isolation_results.csv
mkdir -p ~/trustlens/results

echo "primitive,run,T_avg_ms,U_res_pct,F_rate" > "$OUTPUT"

DURATION=120  # seconds of load

run_isolation() {
  local PROFILE=$1
  local PRIMITIVE=$2
  local RUN=$3

  echo "--- Isolating $PRIMITIVE (run $RUN) with profile $PROFILE ---"

  cd "$TESTBED"

  local COMPOSE_FLAGS="-f docker-compose.yml"
  case "$PROFILE" in
    v1)       COMPOSE_FLAGS="$COMPOSE_FLAGS -f docker-compose.v1.yml" ;;
    v2-mtls)  COMPOSE_FLAGS="$COMPOSE_FLAGS -f docker-compose.v2-mtls.yml" ;;
    v2-vault) COMPOSE_FLAGS="$COMPOSE_FLAGS -f docker-compose.v2-vault.yml" ;;
    v3)       COMPOSE_FLAGS="$COMPOSE_FLAGS -f docker-compose.v3.yml" ;;
    # v2: base docker-compose.yml only
  esac

  docker compose $COMPOSE_FLAGS up -d --build --quiet-pull 2>/dev/null
  sleep 20

  (cd ~/trustlens/gatling && mvn -q gatling:test \
    -Dgatling.simulationClass=trustlens.LocationUpdateSimulation \
    -Dgatling.runDescription="isolation-$PRIMITIVE-run$RUN") \
    2>/dev/null || true

  T_AVG=$(curl -s "http://localhost:9090/api/v1/query" \
    --data-urlencode \
    "query=sum(rate(http_server_requests_seconds_sum[${DURATION}s]))/sum(rate(http_server_requests_seconds_count[${DURATION}s]))*1000" \
    | jq -r '.data.result[0].value[1] // "0"')

  U_RES=$(curl -s "http://localhost:9090/api/v1/query" \
    --data-urlencode \
    "query=avg(process_cpu_usage)*100" \
    | jq -r '.data.result[0].value[1] // "0"')

  F_RATE=$(curl -s "http://localhost:9090/api/v1/query" \
    --data-urlencode \
    "query=sum(rate(http_server_requests_seconds_count{status=~\"5..\"}[${DURATION}s]))/sum(rate(http_server_requests_seconds_count[${DURATION}s]))" \
    | jq -r '.data.result[0].value[1] // "0"')

  echo "$PRIMITIVE,$RUN,$T_AVG,$U_RES,$F_RATE" >> "$OUTPUT"

  docker compose $COMPOSE_FLAGS down -v --remove-orphans 2>/dev/null
  sleep 5
}

# p1 — Implicit Trust (API Gateway only, no JWT)
for RUN in 1 2 3 4 5; do
  run_isolation "v1" "p1" "$RUN"
done

# p2 — JWT validation (stateless, on top of p1)
for RUN in 1 2 3 4 5; do
  run_isolation "v2" "p2" "$RUN"
done

# p3 — mTLS (gateway↔service mutual TLS, on top of p1+p2)
for RUN in 1 2 3 4 5; do
  run_isolation "v2-mtls" "p3" "$RUN"
done

# p4 — Vault dynamic secret rotation (on top of p1+p2+p3)
for RUN in 1 2 3 4 5; do
  run_isolation "v2-vault" "p4" "$RUN"
done

# p5 — Continuous risk evaluation / RiskEvaluator (on top of p1+p2+p3+p4)
for RUN in 1 2 3 4 5; do
  run_isolation "v3" "p5" "$RUN"
done

echo "=== Primitive isolation complete. Results: $OUTPUT ==="
