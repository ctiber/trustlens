# TrustLens — Replication Package

Replication package for the paper:

> **"TrustLens: Runtime Context-Aware Zero Trust Posture Recommendation"**  
> Chouki Tibermacine — IRISA, Université Bretagne Sud

This repository contains all artefacts needed to reproduce the two-phase experiment described in the paper: the containerised testbed, the TrustLens framework (Layer 1 + Layer 2), the Gatling load-test simulations, and the orchestration scripts.

---

## Repository layout

```
.
├── framework/          # TrustLens Python code (Layer 1 + Layer 2)
│   ├── config.py           – all tunable parameters (weights, CUSUM, Prometheus URLs)
│   ├── features.py         – PromQL-based feature vector collection
│   ├── primitives.py       – security primitive / configuration helpers
│   ├── layer1_profiling.py – DBSCAN + k-means regime profiling + Φ scoring
│   ├── layer2_monitoring.py– Mahalanobis + CUSUM online drift detection
│   ├── export_prometheus.py– Prometheus scraper (used during history collection)
│   └── requirements.txt
├── gatling/            # Gatling 3.x load-test simulations (Maven project)
│   ├── pom.xml
│   └── simulations/trustlens/
│       ├── HistorySimulation.scala      – stepped 100→1000 RPS (Phase 1 history)
│       ├── LocationUpdateSimulation.scala – fixed 500 RPS (primitive isolation)
│       ├── LowLoadSimulation.scala
│       ├── PeakLoadSimulation.scala
│       ├── BurstyLoadSimulation.scala
│       ├── MixedAttackSimulation.scala
│       ├── DegradedSimulation.scala
│       ├── ScenarioASimulation.scala    – Lateral movement injection (Phase 2)
│       ├── ScenarioBSimulation.scala    – Credential replay injection (Phase 2)
│       └── ScenarioCSimulation.scala   – Business logic violation (Phase 2)
├── testbed/            # Containerised testbed (Docker Compose + Spring Boot sources)
│   ├── docker-compose.yml          – base stack (Kafka, Redis, Prometheus, Grafana)
│   ├── docker-compose.v1.yml       – V1 overlay (perimeter only)
│   ├── docker-compose.v2-mtls.yml  – V2+mTLS overlay
│   ├── docker-compose.v2-vault.yml – V2+Vault overlay
│   ├── docker-compose.v3.yml       – V3 overlay (full Enhanced Zero Trust)
│   ├── gateway/                    – Nginx gateway configs (plain + mTLS)
│   ├── monitoring/                 – Prometheus scrape config
│   ├── ms-user/                    – Spring Boot user/auth service
│   ├── ms-location/                – Spring Boot GPS telemetry service
│   ├── ms-proximity-detection/     – Kafka consumer, proximity rules
│   └── ms-notification/            – Kafka consumer, alert fanout
├── scripts/            # Experiment orchestration (run in numbered order)
│   ├── 00_build.sh              – Maven build of all microservices
│   ├── 01_gen_certs.sh          – mTLS certificate generation (CA + per-service)
│   ├── 02_setup_python.sh       – Python venv + pip install
│   ├── 03_isolate_primitives.sh – Primitive cost isolation (5 runs × 5 primitives)
│   ├── 04_phase1_profiling.sh   – Phase 1: history collection + Layer 1 profiling
│   ├── 05_phase2_injection.sh   – Phase 2: adversarial injection + Layer 2 monitoring
│   ├── 06_baselines.sh          – Static-V1/V2/V3 baseline measurements
│   ├── 07_analyse.sh            – Post-hoc analysis and summary
│   └── 08_run_all.sh            – Runs 00→07 end-to-end
└── results/            # Paper results (pre-computed, provided for reference)
    ├── rpt.json              – Regime Profile Table produced by Layer 1
    ├── alerts.jsonl          – Layer 2 alerts from Phase 2 (83 alerts, 405 ticks)
    ├── isolation_results.csv – Primitive cost isolation measurements
    └── history_all.csv       – Merged Phase 1 telemetry (924 rows)
```

---

## Prerequisites

| Tool | Version tested | Notes |
|------|---------------|-------|
| Docker + Docker Compose v2 | 24.x | `docker compose` (not `docker-compose`) |
| Java / Maven | JDK 17, Maven 3.9 | For building microservices and Gatling |
| Python | 3.10+ | For TrustLens framework |
| `openssl`, `jq`, `curl` | any recent | For cert generation and isolation script |

Hardware used in the paper: Dell Precision 5490, Intel Core Ultra 7 165H (22 threads), 32 GB RAM, Ubuntu 24.04.4 LTS.  
The experiments are I/O and network-bound rather than CPU-bound; any machine with ≥16 GB RAM and Docker should work, though absolute latency values will differ.

---

## Quick start — full end-to-end replication

**The scripts assume the repository is cloned to `~/trustlens`.**

```bash
git clone https://github.com/ctiber/trustlens ~/trustlens
cd ~/trustlens
bash scripts/08_run_all.sh
```

This runs all phases sequentially (~3–4 hours total) and writes outputs to `~/trustlens/results/`.  
The key output files are:

| File | Contents |
|------|---------|
| `results/isolation_results.csv` | Primitive cost measurements (Table I in paper) |
| `results/rpt.json` | Regime Profile Table — cluster centroids + posture recommendations (Table II) |
| `results/alerts.jsonl` | Layer 2 drift alerts with feature vectors and assigned contexts (Table III) |

---

## Step-by-step reproduction

Run scripts individually if you want to inspect intermediate results.

### Step 0 — Build microservices

```bash
bash scripts/00_build.sh
```

Compiles all four Spring Boot services via Maven (`-DskipTests`).  
Output: `testbed/ms-*/target/*.jar` (referenced by Dockerfiles).

### Step 1 — Generate mTLS certificates

```bash
bash scripts/01_gen_certs.sh
```

Creates a local CA and per-service certificate pairs under `testbed/certs/`.  
Required for any stack that activates `p3` (mTLS, i.e. V2+mTLS, V3 overlays).

### Step 2 — Python environment

```bash
bash scripts/02_setup_python.sh
```

Creates `framework/.venv` and installs dependencies from `framework/requirements.txt`.

### Step 3 — Primitive cost isolation (optional, ~90 min)

```bash
bash scripts/03_isolate_primitives.sh
```

Measures the marginal latency cost ΔT_i of each primitive by enabling one at a time at 500 RPS (5 runs each). Writes `results/isolation_results.csv`.  
Skip this step and use the pre-computed `results/isolation_results.csv` if you want to jump directly to Phase 1.

### Step 4 — Phase 1: history collection + Layer 1 profiling (~90 min)

```bash
bash scripts/04_phase1_profiling.sh
```

1. Brings up the testbed under each of V1, V2, V2+mTLS, V2+Vault, V3 in turn.
2. Runs `HistorySimulation` (stepped 100→1000 RPS) while collecting Prometheus metrics every 5 s.
3. Merges all per-profile CSVs into `results/history_all.csv` (924 rows).
4. Runs `layer1_profiling.py` to cluster the history data (DBSCAN + k-means) and compute posture recommendations via Φ scoring.
5. Writes `results/rpt.json`.

**Expected output** (paper values):
- 4 clusters: R0 (benign, N=637), R1 (adversarial-moderate, N=129), R2 (adversarial-high, N=130), R3 (degraded, N=28)
- Silhouette score: 0.902
- Posture recommendations: V1 for R0/R3, V2 for R1, V2+mTLS for R2

### Step 5 — Phase 2: adversarial injection + Layer 2 monitoring (~60 min)

```bash
bash scripts/05_phase2_injection.sh
```

Runs all three injection scenarios sequentially against the V3 stack, with Layer 2 monitoring throughout:

| Scenario | Attack | Load phase | Expected signal |
|----------|--------|-----------|----------------|
| A — Lateral movement | Direct POST to ms-location, bypassing gateway | Medium (400–600 RPS) | ↑ R_sec |
| B — Credential replay | Replayed pre-rotation JWTs + Redis fault (t+120s, 120s outage) | High (≥700 RPS) | ↑ R_sec, ↑ F_rate |
| C — Business logic violation | Impossible-travel location updates (Rennes→New York) | Low (≤200 RPS) | ↑ R_sec |

Layer 2 polls Prometheus every 5 s (405 ticks total, ≈34 min).  
Output: `results/alerts.jsonl` — one JSONL record per alert with feature vector, assigned context, CUSUM statistic, and recommended posture.

**Expected output** (paper values):
- 83 alerts: 82 genuine + 1 instrumentation artefact
- All 5 injection episodes detected within ≤5 s
- False positive rate: 0 % (post-filtering)

### Steps 6–7 — Baselines and analysis

```bash
bash scripts/06_baselines.sh   # Static-V1/V2/V3 latency measurements
bash scripts/07_analyse.sh     # Summary statistics, Security Tax table
```

---

## Configuration

All tunable parameters live in `framework/config.py`:

```python
# Scoring weights (must sum to 1)
WEIGHT_SECURITY    = 0.7   # w_s — security coverage
WEIGHT_LATENCY     = 0.1   # w_l — latency cost
WEIGHT_RELIABILITY = 0.2   # w_r — failure rate

# Layer 2 drift detection
MAHALANOBIS_OUTLIER_THRESHOLD = 3.0
CUSUM_DELTA = 0.15   # expected shift magnitude
CUSUM_H     = 1.0    # decision threshold h

# DBSCAN
DBSCAN_EPS         = 0.15
DBSCAN_MIN_SAMPLES = 5
```

To run against a remote Prometheus instance, change `PROMETHEUS_URL`.

---

## Security configurations

The testbed implements five security primitives (p1–p5) via Docker Compose overlays:

| Config | Overlay file | Active primitives |
|--------|-------------|-------------------|
| V1 (Implicit Trust) | `docker-compose.v1.yml` | p1 only |
| V2 (Standard ZT) | base only | p1, p2 |
| V2+mTLS | `docker-compose.v2-mtls.yml` | p1, p2, p3 |
| V2+Vault | `docker-compose.v2-vault.yml` | p1, p2, p4 |
| V3 (Enhanced ZT) | `docker-compose.v3.yml` | p1, p2, p3, p4, p5 |

Phase 2 injection scenarios require the V3 stack (p5/RiskEvaluator detects impossible travel; p4/Vault detects replayed credentials).

---

## Pre-computed results

The `results/` directory contains the outputs from the paper's experimental run.  
You can inspect them without re-running the full experiment:

```bash
# View the Regime Profile Table
python3 -m json.tool results/rpt.json

# Count alerts by assigned context
jq -r '.assigned_context' results/alerts.jsonl | sort | uniq -c

# Check peak R_sec during Phase 2
jq 'select(.feature_vector[3] > 0.1) | {tick, context: .assigned_context, r_sec: .feature_vector[3]}' \
   results/alerts.jsonl
```

---

## Citing this work

```bibtex
@inproceedings{tibermacine2026trustlens,
  author    = {Tibermacine, Chouki},
  title     = {TrustLens: Runtime Context-Aware Zero Trust Posture Recommendation},
  booktitle = {<venue>},
  year      = {2026},
  url       = {https://github.com/ctiber/trustlens}
}
```

---

## Licence

Source code: MIT.  
Results data (`results/`): CC BY 4.0.
