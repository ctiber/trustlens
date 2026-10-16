"""
TrustLens – Global Configuration
=================================
Central place for all tunable parameters. Override values here or
load from environment variables / a YAML file in production.
"""

# ── Prometheus ────────────────────────────────────────────────────────────────
PROMETHEUS_URL = "http://localhost:9090"

# Sliding window for feature computation (seconds)
FEATURE_WINDOW_SECONDS = 60

# Polling interval for Layer 2 (seconds)
MONITORING_TICK_SECONDS = 5

# ── Feature collection ────────────────────────────────────────────────────────
# PromQL queries — adapt metric names to your instrumentation
# Application requests only: Prometheus scrapes of /actuator are excluded.
_APP = 'uri!~"/actuator.*"'
PROMQL_T_AVG = (
    'sum(rate(http_server_requests_seconds_sum{{' + _APP + '}}[{window}s])) / '
    'sum(rate(http_server_requests_seconds_count{{' + _APP + '}}[{window}s]))'
)
PROMQL_U_RES = 'avg(process_cpu_usage) * 100'
PROMQL_F_RATE = (
    '(sum(rate(http_server_requests_seconds_count'
    '{{status=~"5..",' + _APP + '}}[{window}s])) or vector(0)) / '
    'sum(rate(http_server_requests_seconds_count{{' + _APP + '}}[{window}s]))'
)
# Security pressure: HTTP 4xx responses plus the would-be denials of the
# primitives running in shadow mode (observation floor, zt_shadow_denied_total).
PROMQL_R_SEC = (
    '((sum(rate(http_server_requests_seconds_count'
    '{{status=~"4..",' + _APP + '}}[{window}s])) or vector(0)) + '
    '(sum(rate(zt_shadow_denied_total[{window}s])) or vector(0))) / '
    'sum(rate(http_server_requests_seconds_count{{' + _APP + '}}[{window}s]))'
)

FEATURE_NAMES = ["T_avg", "U_res", "F_rate", "R_sec"]

# ── Layer 1 – Regime Profiling ────────────────────────────────────────────────
# DBSCAN
DBSCAN_EPS         = 0.15   # neighbourhood radius (on normalised features)
DBSCAN_MIN_SAMPLES = 5

# k-means refinement
KMEANS_MAX_ITER    = 300
KMEANS_N_INIT      = 10

# Scoring weights  w_s + w_l + w_r should sum to 1
WEIGHT_SECURITY    = 0.7    # w_s  (R_sec coverage)
WEIGHT_LATENCY     = 0.1    # w_l  (Cost_T)
WEIGHT_RELIABILITY = 0.2    # w_r  (F_rate)

# RPT persistence
RPT_PATH = "rpt.json"

# ── Layer 2 – Drift Detection ─────────────────────────────────────────────────
# Mahalanobis outlier threshold η
MAHALANOBIS_OUTLIER_THRESHOLD = 3.0

# CUSUM parameters
# Calibrated to observed testbed d_M range (0.05–0.45); attacks sustain d_M ≈ 0.22.
# kappa = CUSUM_DELTA/2 = 0.075 → accumulation starts at d_M > mu0+0.075 ≈ 0.18
CUSUM_DELTA = 0.15          # expected shift magnitude to detect
CUSUM_H     = 1.0           # decision threshold h

# Alert output
ALERT_LOG_PATH = "trustlens_alerts.jsonl"

# ── Security Primitive Catalogue ──────────────────────────────────────────────
# Each entry: (id, name, type, default_activation)
# type: "binary" | "graduated"
PRIMITIVES = [
    ("p1", "Perimeter control",          "binary",    1.0),
    ("p2", "Local identity validation",  "binary",    0.0),
    ("p3", "Mutual TLS",                 "binary",    0.0),
    ("p4", "Dynamic secret rotation",    "graduated", 0.0),
    ("p5", "Continuous risk evaluation", "graduated", 0.0),
]

# Empirically measured marginal costs per primitive — TRUE isolation study
# (03_isolate_primitives_zt.sh: each primitive enabled alone on top of p1,
# 500 users/s, 120 s, 5 interleaved runs; results_zt/isolation_results.csv).
# delta_T: server-side mean response time T_avg, seconds (vs baseline 0.588 ms)
# delta_U: process CPU usage, fraction. Non-significant negative values -> 0.
#   p2 +0.061 ms (+10.3%, p<0.001)   p3 +0.110 ms (+18.8%, p=0.004)
#   p4 -0.001 ms (n.s., p=0.90)      p5 +0.609 ms (+103.6%, p<0.001) — dominant
PRIMITIVE_COSTS = {
    "p1": {"delta_T": 0.000000, "delta_U": 0.000000},  # baseline (always on)
    "p2": {"delta_T": 0.000061, "delta_U": 0.0017},    # JWT at every service
    "p3": {"delta_T": 0.000110, "delta_U": 0.0023},    # mTLS HTTP + Kafka (keep-alive)
    "p4": {"delta_T": 0.000000, "delta_U": 0.0000},    # Vault rotation (n.s.)
    "p5": {"delta_T": 0.000609, "delta_U": 0.0044},    # synchronous risk analysis
}

# Dependencies: a primitive only protects when the primitives it relies on are
# active. Key rotation (p4) invalidates tokens only if tokens are verified (p2);
# the closed-loop experiment showed p4 alone letting 100% of replays through.
PRIMITIVE_DEPENDENCIES = {
    "p4": ("p2",),
}

# Reference configurations (V1 / V2 / V3)
CONFIG_V1 = {"p1": 1, "p2": 0, "p3": 0, "p4": 0.0, "p5": 0.0}
CONFIG_V2 = {"p1": 1, "p2": 1, "p3": 0, "p4": 0.0, "p5": 0.0}
CONFIG_V3 = {"p1": 1, "p2": 1, "p3": 1, "p4": 1.0, "p5": 1.0}