#!/usr/bin/env bash
# run-variant.sh <variant>   — start the testbed with variants/<variant>.env
# run-variant.sh --down      — stop everything
#
# Mirrors ZT-Tax scripts/run-variant.sh: reads the ZT flags of the variant,
# activates the matching compose profiles (sr → Vault, ra → risk-analysis),
# selects the mTLS configuration, then waits until the stack serves requests.
set -euo pipefail
TESTBED="$(cd "$(dirname "$0")" && pwd)"
COMPOSE=(docker compose -f "$TESTBED/docker-compose.zt.yml" -p testbed)

if [[ "${1:-}" == "--down" ]]; then
  "${COMPOSE[@]}" --profile sr --profile ra down -v --remove-orphans >/dev/null 2>&1 || true
  exit 0
fi

VARIANT="${1:?usage: run-variant.sh <variant>|--down}"
ENV_FILE="$TESTBED/variants/${VARIANT}.env"
[[ -f "$ENV_FILE" ]] || { echo "unknown variant: $VARIANT"; ls "$TESTBED/variants"; exit 1; }

getv() { grep -E "^$1=" "$ENV_FILE" | cut -d= -f2- || true; }
export ZT_VARIANT_ENV="variants/${VARIANT}.env"
export ZT_MTLS_MODE="$(getv ZT_MTLS_MODE)"
export ZT_HTTP_MTLS_MODE="$(getv ZT_HTTP_MTLS_MODE)"; [[ -z "$ZT_HTTP_MTLS_MODE" ]] && unset ZT_HTTP_MTLS_MODE
export ZT_GATEWAY_CONF="$(getv ZT_GATEWAY_CONF)";     [[ -z "$ZT_GATEWAY_CONF" ]] && unset ZT_GATEWAY_CONF
# Closed loop: the gateway reads gateway/active.conf, rewritten in place and
# hot-reloaded by closed_loop_controller.py when p3 is switched.
if [[ "${ZT_GATEWAY_CONF:-}" == "active.conf" ]]; then
  if [[ "$(getv ZT_MTLS)" == "true" ]]; then SRC=nginx-dual-on.conf; else SRC=nginx-dual-off.conf; fi
  cat "$TESTBED/gateway/$SRC" > "$TESTBED/gateway/active.conf"
fi
SR_LEVEL="$(getv ZT_SR_LEVEL)"; BASE="$(getv ZT_SR_BASE_PERIOD_S)"
export ZT_SR_ROTATION_PERIOD_S="$(python3 -c "print(max(1, round(${BASE:-60}/max(${SR_LEVEL:-1},0.01))))")"

PROFILE_FLAGS=()
IFS=',' read -ra PROFILES <<< "$(getv ZT_PROFILES)"
for p in "${PROFILES[@]}"; do [[ -n "$p" ]] && PROFILE_FLAGS+=(--profile "$p"); done

echo "▶ variant=${VARIANT}  mtls=${ZT_MTLS_MODE}  profiles=${PROFILES[*]:-none}  T_rot=${ZT_SR_ROTATION_PERIOD_S}s"
"$TESTBED/run-variant.sh" --down
( cd "$TESTBED" && "${COMPOSE[@]}" "${PROFILE_FLAGS[@]}" up -d >/dev/null 2>&1 )

# ── Wait until a full login → location update round-trip succeeds ──────────
for i in $(seq 1 90); do
  TOKEN=$(curl -s -m 3 -X POST localhost:8080/auth/login -H 'Content-Type: application/json' \
          -d '{"username":"warmup","device":"warmup-device"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p' || true)
  if [[ -n "$TOKEN" ]]; then
    CODE=$(curl -s -m 3 -o /dev/null -w '%{http_code}' -X POST \
           "localhost:8080/location/update?lat=48.0&lon=-2.0" -H "Authorization: Bearer $TOKEN" || true)
    H3=$(curl -s -m 3 -o /dev/null -w '%{http_code}' localhost:8083/actuator/health || true)
    H4=$(curl -s -m 3 -o /dev/null -w '%{http_code}' localhost:8084/actuator/health || true)
    if [[ "$CODE" == 200 && "$H3" == 200 && "$H4" == 200 ]]; then
      echo "✓ ${VARIANT} ready after $((i*2))s"
      sleep 10   # let Kafka consumer groups settle and Prometheus scrape once
      exit 0
    fi
  fi
  sleep 2
done
echo "✗ ${VARIANT} not ready after 180s"; exit 1
