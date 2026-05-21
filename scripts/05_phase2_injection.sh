#!/bin/bash
# Phase 2 — three attack scenarios injected against V3 stack.
# V3 is required so that:
#   - RiskEvaluator queries Redis → Redis fault generates F_rate (5xx) signal
#   - Impossible-travel check blocks Scenario C requests → R_sec (4xx) signal
#   - mTLS rejects direct-bypass requests in Scenario A
# The Layer 2 monitor runs continuously across all three scenarios.
set -e

TESTBED=~/trustlens/testbed
FRAMEWORK=~/trustlens/framework
RESULTS=~/trustlens/results

source "$FRAMEWORK/.venv/bin/activate"

# ── Stack helpers ─────────────────────────────────────────────────────────────

stack_up() {
  cd "$TESTBED"
  docker compose -f docker-compose.yml -f docker-compose.v3.yml \
    up -d --build --quiet-pull 2>/dev/null
  sleep 25
}

stack_down() {
  cd "$TESTBED"
  docker compose -f docker-compose.yml -f docker-compose.v3.yml \
    down -v --remove-orphans 2>/dev/null
  sleep 10
}

# ── Initial stack + monitor ───────────────────────────────────────────────────

stack_up

rm -f "$RESULTS/alerts.jsonl"
touch "$RESULTS/alerts.jsonl"
echo "Previous alerts cleared."

python3 "$FRAMEWORK/layer2_monitoring.py" \
  --rpt    "$RESULTS/rpt.json" \
  --alert  "$RESULTS/alerts.jsonl" \
  --warmup 20 &
MONITOR_PID=$!
echo "Layer 2 monitor started (PID=$MONITOR_PID)"
sleep 120

# ── Scenario A — Lateral Movement ────────────────────────────────────────────
echo "=== Injecting Scenario A ==="
(cd ~/trustlens/gatling && mvn -q gatling:test \
  -Dgatling.simulationClass="trustlens.ScenarioASimulation" \
  -Dgatling.runDescription="scenario-A") 2>/dev/null || true
sleep 30

# ── Scenario B — Credential Replay + Redis fault ──────────────────────────────
# Restart stack so Redis state is clean before the fault window.
stack_down
stack_up
echo "=== Injecting Scenario B (with Redis fault at t=120s) ==="

# Redis fault: stop at t=120s into B, down for 120s, then restart.
# RiskEvaluator (V3) queries Redis on every request → 5xx during outage → F_rate signal.
(
  sleep 120
  REDIS_CTR=$(docker ps --filter "name=redis" --format "{{.Names}}" | head -1)
  echo "  [fault] Stopping $REDIS_CTR for 120s..."
  docker stop "$REDIS_CTR"
  sleep 120
  docker start "$REDIS_CTR"
  echo "  [fault] Redis restarted."
) &
FAULT_PID=$!

(cd ~/trustlens/gatling && mvn -q gatling:test \
  -Dgatling.simulationClass="trustlens.ScenarioBSimulation" \
  -Dgatling.runDescription="scenario-B") 2>/dev/null || true

wait "$FAULT_PID" 2>/dev/null || true
sleep 30

# ── Scenario C — Business Logic Violation ─────────────────────────────────────
stack_down
stack_up
echo "=== Injecting Scenario C ==="
(cd ~/trustlens/gatling && mvn -q gatling:test \
  -Dgatling.simulationClass="trustlens.ScenarioCSimulation" \
  -Dgatling.runDescription="scenario-C") 2>/dev/null || true
sleep 30

# ── Teardown ──────────────────────────────────────────────────────────────────
kill $MONITOR_PID 2>/dev/null || true
stack_down
echo "=== Phase 2 complete. Alerts: $RESULTS/alerts.jsonl ==="
