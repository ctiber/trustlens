#!/bin/bash
# Shared helpers for the fine-grained ZT experiment scripts (*_zt.sh).
TESTBED=$HOME/trustlens/testbed
FRAMEWORK=$HOME/trustlens/framework
RESULTS=$HOME/trustlens/results_zt
GATLING=$HOME/trustlens/gatling
GATLING_OUT=$HOME/trustlens/results/gatling
PROM=http://localhost:9090
mkdir -p "$RESULTS"

variant_up()   { "$TESTBED/run-variant.sh" "$1"; }
variant_down() { "$TESTBED/run-variant.sh" --down; sleep 5; }

# run_gatling <SimulationClass> <description>  → prints the report directory
run_gatling() {
  local marker; marker=$(mktemp); sleep 1
  (cd "$GATLING" && mvn -q gatling:test \
     -Dgatling.simulationClass="trustlens.$1" \
     -Dgatling.runDescription="$2") >/dev/null 2>&1 || true
  find "$GATLING_OUT" -mindepth 1 -maxdepth 1 -type d -newer "$marker" | sort | tail -1
  rm -f "$marker"
}

# prom <promql> [time]  → scalar value (0 if empty)
prom() {
  local args=(--data-urlencode "query=$1"); [ -n "${2:-}" ] && args+=(--data-urlencode "time=$2")
  curl -s "$PROM/api/v1/query" "${args[@]}" | python3 -c \
    'import json,sys; r=json.load(sys.stdin)["data"]["result"]; print(r[0]["value"][1] if r else "0")'
}
