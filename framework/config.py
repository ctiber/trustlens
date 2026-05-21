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
PROMQL_T_AVG = (
    'sum(rate(http_server_requests_seconds_sum[{window}s])) / '
    'sum(rate(http_server_requests_seconds_count[{window}s]))'
)
PROMQL_U_RES = 'avg(process_cpu_usage) * 100'
PROMQL_F_RATE = (
    '(sum(rate(http_server_requests_seconds_count'
    '{{status=~"5.."}}[{window}s])) or vector(0)) / '
    'sum(rate(http_server_requests_seconds_count[{window}s]))'
)
PROMQL_R_SEC = (
    '(sum(rate(http_server_requests_seconds_count'
    '{{status=~"4.."}}[{window}s])) or vector(0)) / '
    'sum(rate(http_server_requests_seconds_count[{window}s]))'
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

# Empirically measured marginal costs per primitive (ablation study)
# delta_T in seconds (marginal latency vs previous cumulative config)
# delta_U in normalised resource units (clamped to 0 when negative — measurement noise)
# p3 dominates: mTLS handshake introduces a x20 latency jump over p2.
# p4 and p5 are statistically indistinguishable from p3 at 500 RPS (absorbed in p3 variance).
PRIMITIVE_COSTS = {
    "p1": {"delta_T": 0.000000, "delta_U": 0.000000},  # baseline
    "p2": {"delta_T": 0.000055, "delta_U": 0.000174},  # JWT validation +20.5%
    "p3": {"delta_T": 0.006194, "delta_U": 0.000000},  # mTLS handshake +1920% — dominant cost
    "p4": {"delta_T": 0.000376, "delta_U": 0.000000},  # Vault retrieval +5.8% (n.s. at 500 RPS)
    "p5": {"delta_T": 0.000000, "delta_U": 0.000000},  # RiskEvaluator  (n.s. — absorbed in p3)
}

# Reference configurations (V1 / V2 / V3)
CONFIG_V1 = {"p1": 1, "p2": 0, "p3": 0, "p4": 0.0, "p5": 0.0}
CONFIG_V2 = {"p1": 1, "p2": 1, "p3": 0, "p4": 0.0, "p5": 0.0}
CONFIG_V3 = {"p1": 1, "p2": 1, "p3": 1, "p4": 1.0, "p5": 1.0}