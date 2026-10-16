#!/bin/sh
# vault/init.sh — Bootstrap Vault for p4 (Secret Rotation). Runs as the
# vault-init container (compose profile "sr"), as in the ZT-Tax package, then
# keeps running as the rotator: every ROTATION_PERIOD_S seconds a new JWT
# secret is written, creating a new KV v2 version (= JWT key version, kid).
#   ROTATION_PERIOD_S = ZT_SR_BASE_PERIOD_S / a4   (graduated activation)
set -e
export VAULT_ADDR="${VAULT_ADDR:-http://vault:8200}"

echo "[vault-init] Waiting for Vault at $VAULT_ADDR..."
until vault status >/dev/null 2>&1; do sleep 1; done

vault secrets enable -path=secret kv-v2 2>/dev/null || echo "[vault-init] kv-v2 already enabled"
vault kv metadata put -max-versions=20 secret/trustlens >/dev/null 2>&1 || true

new_secret() { head -c 48 /dev/urandom | base64 | tr -d '\n'; }

vault kv put secret/trustlens jwt_secret="$(new_secret)" >/dev/null
echo "[vault-init] Initial JWT secret written to secret/trustlens"

vault policy write trustlens-service - >/dev/null <<'POL'
path "secret/data/trustlens" { capabilities = ["read"] }
path "secret/metadata/trustlens" { capabilities = ["read"] }
POL

PERIOD="${ROTATION_PERIOD_S:-60}"
echo "[vault-init] Rotating the JWT secret every ${PERIOD}s"
while true; do
  sleep "$PERIOD"
  V=$(vault kv put -format=json secret/trustlens jwt_secret="$(new_secret)" | sed -n 's/.*"version": \([0-9]*\).*/\1/p')
  echo "[vault-init] JWT secret rotated -> v${V}"
done
