# TrustLens — Replication Package

Replication package for the paper:

> **"TrustLens: A Framework for Adaptive Security Posture to Minimise the Zero Trust Security Tax"**
> Chouki Tibermacine — IRISA, Université Bretagne Sud

This repository contains everything needed to reproduce the experiments: the
containerised event-driven testbed with its Zero Trust primitives, the TrustLens
framework (Layer 1 profiling, Layer 2 drift detection), the Gatling load tests,
the orchestration scripts, the analysis script and the pre-computed results.

Each security primitive is an **independent feature flag**, so any subset of the
primitive space can be deployed from the same images. The implementation of the
primitives follows the ZT-Tax replication package (fine-grained Access Control on
All services, Secret Rotation, mutual TLS and Risk Analysis).

> The previous version of the package (cumulative Spring profiles V1/V2/V3,
> cumulative ablation) is kept for reference in [`legacy/`](legacy/).

---

## Repository layout

```
.
├── framework/                 # TrustLens (Python)
│   ├── config.py                 – parameters, PromQL, measured primitive costs, dependencies
│   ├── features.py               – feature vector f = (T_avg, U_res, F_rate, R_sec) from Prometheus
│   ├── primitives.py             – posture space (72 candidates), m(s) with dependencies, Φ, recommend()
│   ├── layer1_profiling.py       – DBSCAN + k-means regime profiling → Regime Profile Table (RPT)
│   ├── layer2_monitoring.py      – nearest-context assignment + CUSUM drift alerts
│   ├── export_prometheus.py      – feature exporter (history collection)
│   ├── closed_loop_controller.py – operator simulator applying recommendations at runtime (Phase 3)
│   └── requirements.txt
├── testbed/
│   ├── docker-compose.zt.yml     – full stack; profiles: sr (Vault), ra (risk analysis)
│   ├── run-variant.sh            – start a variant (variants/*.env) and wait until it serves requests
│   ├── variants/                 – one .env per posture (v1, v2, v2-mtls, v2-mtls-sr, v3, iso-*, cl-*)
│   ├── zt/                       – mTLS environment files (services, Kafka clients, broker)
│   ├── zt-common/                – shared Java ZT primitives (filters, key providers, posture endpoint)
│   ├── risk-analysis/            – risk-analysis service (Node.js), primitive p5
│   ├── vault/init.sh             – Vault bootstrap + JWT key rotator, primitive p4
│   ├── gateway/                  – nginx gateway (p1): plain / mTLS / closed-loop configurations
│   ├── monitoring/prometheus-zt.yml
│   └── ms-user, ms-location, ms-proximity-detection, ms-notification   – Spring Boot services
├── gatling/                   # Gatling 3.9 simulations (Maven)
├── scripts/                   # Orchestration and analysis (see below)
├── results_zt/                # Pre-computed results of the paper
└── legacy/                    # Previous, profile-based version of the package
```

---

## Security primitives

| Id | Primitive | Flag | Implementation |
|----|-----------|------|----------------|
| p1 | Perimeter control | always on | nginx gateway, single ingress point |
| p2 | JWT verification at every service | `ZT_AC4A` | servlet filter in every service; JWT propagated as a Kafka header and verified by the consumers |
| p3 | Mutual TLS | `ZT_MTLS` | gateway→services HTTPS with client certificates over keep-alive pools; Kafka SSL listener with `ssl.client.auth=required`, plaintext listener closed |
| p4 | Secret rotation (graduated) | `ZT_SR`, `ZT_SR_LEVEL` | JWT key in Vault KV v2, rotated every `60 s / a4`; JWT `kid` = Vault version, current and previous accepted |
| p5 | Continuous risk evaluation (graduated) | `ZT_RA`, `ZT_RA_LEVEL` | synchronous call per request to the risk-analysis service (privilege escalation, rate limit, lateral movement, IP change, device mismatch, impossible travel); `a5` = sampled fraction |

**Observation floor (shadow mode).** `ZT_AC4A_SHADOW` and `ZT_RA_SHADOW_LEVEL`
make p2 and p5 evaluate requests without blocking them when they are not
enforced; would-be denials are exported as `zt_shadow_denied_total` and added
to the `R_sec` feature. p5 in shadow mode samples *users*, not requests, so that
stateful detectors keep working. p2, p3 (gateway hop) and p5 can be switched at
runtime through `POST /zt/posture` on each service (closed-loop experiment).

**Dependencies.** p4 only protects when p2 is enforced (rotating a key that
nobody verifies blocks nothing); this is modelled in `m(s)` through
`PRIMITIVE_DEPENDENCIES` in `framework/config.py`.

---

## Prerequisites

| Tool | Version tested |
|------|----------------|
| Docker + Docker Compose v2 | Compose 2.29 |
| Java / Maven | JDK 17+ (21 tested), Maven 3.9 |
| Node.js / npm | 18+ (for the risk-analysis dependencies) |
| Python | 3.10+ |
| `openssl`, `keytool`, `curl`, `jq` | any recent |

Hardware used in the paper: Dell Precision 5490, Intel Core Ultra 7 165H
(22 threads), 32 GB RAM, Ubuntu 24.04.4 LTS. Absolute latencies will differ on
other machines; the comparisons between postures are what matters.

**The scripts assume the repository is cloned to `~/trustlens`.**

---

## Reproducing the experiments

```bash
git clone https://github.com/ctiber/trustlens ~/trustlens
cd ~/trustlens
bash scripts/00_build.sh        # zt-common, the 4 services, npm deps of risk-analysis, Docker images
bash scripts/01_gen_certs.sh    # CA and certificates in testbed/certs-zt/
bash scripts/02_setup_python.sh # framework/.venv
```

| Step | Script | Duration | Output (`results_zt/`) |
|------|--------|----------|------------------------|
| Primitive cost isolation: each primitive alone on V1, plus V3 and p5 at a5 = 0.5; 5 interleaved runs, 30 s warm-up, 500 users/s × 120 s | `03_isolate_primitives_zt.sh` | ~2 h | `isolation_results.csv` |
| Security Tax of the cumulative postures (V1, V2, V2+mTLS, V2+mTLS+Vault, V3) under stepped load 100→1000 users/s | `04f_posture_history_zt.sh` | ~1 h | `history_<posture>.csv`, `posture_gatling.csv` |
| Phase 1: 7 history scenarios + Layer 1 | `04_phase1_profiling_zt.sh` | ~1.5 h | `history_all.csv`, `rpt.json` |
| Phase 2: scenarios A, B, C (+ Redis outage) under Static-V3, Layer 2 monitoring | `05_phase2_injection_zt.sh` | ~45 min | `alerts.jsonl`, `phase2_*` |
| Observation-floor cost + Phase 3 closed loop (5 TrustLens runs, 2 Static-light, 2 Static-V3) | `11_floor_and_closed_loop_zt.sh` | ~4.5 h | `closed_loop/<mode>_run<N>/` |
| All of the above except Phase 3 | `09_run_all_zt.sh` | ~5.5 h | |

Scripts are resumable: completed runs are skipped. Then:

```bash
source framework/.venv/bin/activate
python3 scripts/07_analyse_zt.py all    # → results_zt/analysis.json + printed tables
```

The analysis reads the raw Gatling reports (`results/gatling/`, not
versioned) for end-to-end latencies and attack acceptance; the pre-computed
values are in `results_zt/analysis.json`.

To start a single posture manually:

```bash
cd ~/trustlens/testbed
./run-variant.sh v3          # or v1, v2, v2-mtls, v2-mtls-sr, iso-p3, iso-p4, iso-p5, cl-light ...
./run-variant.sh --down
```

---

## Pre-computed results (`results_zt/`)

| File | Contents |
|------|----------|
| `isolation_results.csv` | per-run cost isolation measurements (server-side T_avg, U_res, RA call time) |
| `posture_gatling.csv`, `history_v*.csv` | Security Tax of the cumulative postures |
| `history_<scenario>.csv`, `history_all.csv` | Phase 1 telemetry (924 observations) |
| `rpt.json` | Regime Profile Table (with primitive dependencies); `rpt_before_dependencies.json`: without |
| `alerts.jsonl`, `phase2_*.csv/log` | Phase 2 alerts, monitor log, features and event timeline |
| `closed_loop/` | Phase 3 runs: alerts, monitor log, features, posture switches, controller log |
| `closed_loop_nofloor/` | preliminary closed-loop run without observation floor |
| `analysis.json` | every figure reported in the paper, computed by `07_analyse_zt.py` |

Headline results:

- **Isolation (server-side T_avg vs V1 = 0.59 ms).** p2 +0.06 ms (+10 %),
  p3 +0.11 ms (+19 %), p4 ≈ 0, p5 +0.61 ms (+104 %); V3 is super-additive
  (×1.43 the sum of the primitives); the observation floor costs +36 %.
- **Phase 1.** 4 contexts (benign, adversarial, two degraded), silhouette 0.817.
- **Phase 2.** Credential replay and business-logic attacks detected in 17 s and
  21 s, no false positive; lateral movement stopped by mTLS during the
  handshake leaves no HTTP-level trace.
- **Phase 3.** Without observation floor every attack is accepted. With it,
  TrustLens escalates to the full posture 52 s after the first attack, lets
  43 % of the first wave through and blocks all subsequent attacks.

---

## Configuration

All tunable parameters live in `framework/config.py` (scoring weights,
DBSCAN/CUSUM parameters, PromQL queries, primitive costs and dependencies).
Testbed postures are plain `.env` files in `testbed/variants/`.

---

## Citing this work

```bibtex
@misc{tibermacine2026trustlens,
  author    = {Tibermacine, Chouki},
  title     = {TrustLens: A Framework for Adaptive Security Posture to Minimise the Zero Trust Security Tax},
  year      = {2026},
  url       = {https://github.com/ctiber/trustlens}
}
```

---

## Licence

Source code: MIT.
Results data (`results_zt/`, `legacy/results/`): CC BY 4.0.
