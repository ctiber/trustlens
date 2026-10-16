# TrustLens Testbed

Event-driven microservice testbed for the TrustLens experimental evaluation.
Three trust configurations (V1 / V2 / V3) are activated via Spring profiles
and Docker Compose overrides.

## Structure

```
testbed/
├── docker-compose.yml          # base (V2 default)
├── docker-compose.v1.yml       # V1 override — Implicit Trust
├── docker-compose.v3.yml       # V3 override — mTLS + Vault + RiskEvaluator
├── gateway/nginx.conf          # API Gateway
├── monitoring/prometheus.yml   # Prometheus scrape config
├── ms-user/                    # Identity & session service (PEP)
├── ms-location/                # GPS telemetry producer
└── ms-proximity-detection/     # Kafka consumer + Redis proximity rules
```

## Prerequisites

- Docker 24+ and Docker Compose v2
- Java 21 + Maven 3.9 (for local builds)
- For V3: generate mTLS certificates (see `certs/` section below)

## Running the testbed

### V2 (default — Standard Zero Trust)
```bash
docker-compose up --build
```

### V1 (Implicit Trust)
```bash
docker-compose -f docker-compose.yml -f docker-compose.v1.yml up --build
```

### V3 (Enhanced Zero Trust)
```bash
# 1. Generate certificates first
./certs/generate-certs.sh

# 2. Start with V3 override
docker-compose -f docker-compose.yml -f docker-compose.v3.yml up --build
```

## Endpoints

| Service      | URL                              | Description              |
|---|---|---|
| API Gateway  | http://localhost:8080            | Entry point              |
| ms-user      | http://localhost:8081            | Login / user profile     |
| ms-location  | http://localhost:8082            | GPS update               |
| Prometheus   | http://localhost:9090            | Metrics                  |
| Grafana      | http://localhost:3000            | Dashboards (admin/admin) |

## Quick smoke test

```bash
# 1. Login and get a JWT
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"user1","device":"test-device"}' \
  | jq -r .token)

# 2. Post a location update
curl -X POST "http://localhost:8080/location/update?lat=48.11&lon=-1.68" \
  -H "Authorization: Bearer $TOKEN"
```

## Generating mTLS certificates (V3)

```bash
mkdir -p certs/{ms-user,ms-location,ms-proximity-detection}
# Self-signed CA
openssl req -x509 -newkey rsa:4096 -keyout certs/ca.key \
  -out certs/ca.crt -days 365 -nodes -subj "/CN=TrustLens-CA"

# Per-service keypairs (repeat for each service)
for SVC in ms-user ms-location ms-proximity-detection; do
  openssl req -newkey rsa:2048 -keyout certs/$SVC/key.pem \
    -out certs/$SVC/csr.pem -nodes -subj "/CN=$SVC"
  openssl x509 -req -in certs/$SVC/csr.pem -CA certs/ca.crt \
    -CAkey certs/ca.key -CAcreateserial \
    -out certs/$SVC/cert.pem -days 365
  # Bundle into PKCS12
  openssl pkcs12 -export -out certs/$SVC/keystore.p12 \
    -inkey certs/$SVC/key.pem -in certs/$SVC/cert.pem \
    -passout pass:changeit
  keytool -importcert -noprompt -file certs/ca.crt \
    -keystore certs/$SVC/truststore.p12 -storetype PKCS12 \
    -storepass changeit -alias trustlens-ca
done
```

## Prometheus features collected

| Feature      | PromQL (excerpt)                              |
|---|---|
| T_avg        | `histogram_quantile(0.50, rate(http_server_requests_seconds_bucket[60s]))` |
| U_res        | CPU + memory composite via `process_cpu_seconds_total` |
| F_rate       | `rate(http_server_requests_seconds_count{status=~"5.."}[60s])` |
| R_sec        | `rate(http_server_requests_seconds_count{status=~"4.."}[60s])` |

---

## Fine-grained Zero Trust primitives (current setup)

The profile-based setup above (`docker-compose.v*.yml`, Spring profiles
v1/v2/v2-mtls/v2-vault/v3) is superseded by independent per-primitive flags,
ported from the ZT-Tax replication package (`../zt_tax`):

| Flag | Primitive | Implementation |
|---|---|---|
| — | p1 perimeter | nginx gateway, single ingress (`gateway/nginx-{off,on}.conf`) |
| `ZT_AC4A` | p2 JWT at every service | `zt-common` `Ac4aFilter`; JWT propagated as Kafka header and verified by consumers (`KafkaAuth`) |
| `ZT_MTLS` | p3 mTLS | gateway→services HTTPS with client certs over keep-alive pools; Kafka SSL listener with `ssl.client.auth=required`, plaintext listener closed (`zt/mtls-on.*.env`) |
| `ZT_SR` (+`ZT_SR_LEVEL`) | p4 secret rotation | JWT key in Vault KV v2, rotated every `60 s / a4` by `vault/init.sh`; `kid` = Vault version, current + previous accepted (`VaultJwtKeyProvider`) |
| `ZT_RA` (+`ZT_RA_LEVEL`) | p5 risk evaluation | synchronous call per request to `risk-analysis/` (Node.js); `a5` = sampling rate (`RiskAnalysisFilter`) |

```bash
cd ~/trustlens && ./00_build.sh      # (mvn install zt-common first, see below)
./01_gen_certs.sh                     # CA + certs in testbed/certs-zt
cd testbed/risk-analysis && npm install --omit=dev && cd ..
docker compose -f docker-compose.zt.yml -p testbed --profile sr --profile ra build
./run-variant.sh v3                   # variants/*.env: v1 v2 v2-mtls v2-mtls-sr v3 iso-p3 iso-p4 iso-p5 ...
./run-variant.sh --down
```

Build order: `cd zt-common && mvn install`, then each `ms-*` with `mvn package`.
Experiments: `~/trustlens/09_run_all_zt.sh` (isolation → posture histories →
Phase 1 → Phase 2), results in `~/trustlens/results_zt/`, numbers for the paper
from `07_analyse_zt.py`.
