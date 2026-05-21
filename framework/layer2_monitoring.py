"""
TrustLens – Layer 2: Runtime Drift Detection
=============================================
Online script that:
  1. Loads the RPT produced by Layer 1
  2. Polls Prometheus every MONITORING_TICK_SECONDS
  3. Assigns the current feature vector to the nearest regime
     using Mahalanobis distance
  4. Runs a CUSUM control chart on the distance sequence
  5. Emits a structured alert (JSON) when S(t) ≥ h

Usage:
    python layer2_monitoring.py [--rpt rpt.json] [--alert alerts.jsonl]
"""

from __future__ import annotations
import argparse
import json
import logging
import time
from dataclasses import dataclass, asdict
from pathlib import Path

import numpy as np
from sklearn.preprocessing import MinMaxScaler

from config import (
    MONITORING_TICK_SECONDS,
    MAHALANOBIS_OUTLIER_THRESHOLD,
    CUSUM_DELTA, CUSUM_H,
    RPT_PATH, ALERT_LOG_PATH,
    FEATURE_NAMES,
    WEIGHT_SECURITY, WEIGHT_LATENCY, WEIGHT_RELIABILITY,
)
from features import FeatureCollector, FeatureVector
from primitives import enumerate_binary_configurations

logger = logging.getLogger(__name__)


# ── RPT loader ────────────────────────────────────────────────────────────────

@dataclass
class RegimeEntry:
    regime_id: int
    centroid: np.ndarray
    cov_inv: np.ndarray
    feature_ranges: dict
    recommended_config: dict
    score: float


def load_rpt(path: str = RPT_PATH) -> tuple[list[RegimeEntry], MinMaxScaler]:
    """Load RPT from JSON and reconstruct regime objects + scaler."""
    data = json.loads(Path(path).read_text())

    scaler = MinMaxScaler()
    scaler.data_min_   = np.array(data["scaler_min"])
    scaler.data_max_   = np.array(data["scaler_max"])
    scaler.scale_      = 1.0 / (scaler.data_max_ - scaler.data_min_ + 1e-9)
    scaler.data_range_ = scaler.data_max_ - scaler.data_min_
    scaler.feature_range = (0, 1)
    scaler.min_        = -scaler.data_min_ * scaler.scale_   # required by transform()
    scaler.n_features_in_ = len(data["scaler_min"])
    scaler.n_samples_seen_ = 0

    regimes = []
    for r in data["regimes"]:
        centroid = np.array(r["centroid"])
        # Covariance is not stored in RPT to keep it compact;
        # use identity as fallback (equivalent to normalised Euclidean)
        n = len(centroid)
        cov_inv  = np.eye(n)
        regimes.append(RegimeEntry(
            regime_id          = r["regime_id"],
            centroid           = centroid,
            cov_inv            = cov_inv,
            feature_ranges     = r["feature_ranges"],
            recommended_config = r["recommended_config"],
            score              = r["score"],
        ))

    logger.info("RPT loaded: %d regimes from %s", len(regimes), path)
    return regimes, scaler


# ── Mahalanobis distance ──────────────────────────────────────────────────────

def mahalanobis(f: np.ndarray, regime: RegimeEntry) -> float:
    diff = f - regime.centroid
    return float(np.sqrt(np.maximum(diff @ regime.cov_inv @ diff, 0.0)))


def assign_regime(
    f: np.ndarray,
    regimes: list[RegimeEntry],
    outlier_threshold: float = MAHALANOBIS_OUTLIER_THRESHOLD,
) -> tuple[RegimeEntry | None, float, bool]:
    """
    Assign f to the nearest regime.

    When multiple regimes share similar T_avg and U_res but differ on
    R_sec (index 3), we weight R_sec 10x more so that adversarial
    observations (non-zero R_sec) are correctly assigned to the
    adversarial regime rather than to a benign one with lower Euclidean
    distance in the T_avg/U_res dimensions.

    Returns
    -------
    (nearest_regime, distance, is_uncharted)
    """
    # Weight vector: upweight R_sec (feature index 3) strongly
    weights = np.array([1.0, 1.0, 1.0, 10.0])

    distances = []
    for r in regimes:
        diff = f - r.centroid
        d = float(np.sqrt(np.sum((diff * weights) ** 2)))
        distances.append((r, d))

    nearest, d_min = min(distances, key=lambda x: x[1])
    is_uncharted = d_min > outlier_threshold
    return nearest, d_min, is_uncharted


# ── CUSUM control chart ───────────────────────────────────────────────────────

class CUSUMDetector:
    """
    One-sided CUSUM for detecting upward shifts in d_M(t).

    Parameters
    ----------
    mu0   : in-control mean of d_M (estimated from historical data)
    delta : expected shift magnitude to detect
    h     : decision threshold
    """

    def __init__(
        self,
        mu0: float,
        delta: float = CUSUM_DELTA,
        h: float     = CUSUM_H,
    ):
        self.mu0   = mu0
        self.kappa = delta / 2.0   # allowance parameter κ
        self.h     = h
        self.S     = 0.0           # CUSUM statistic
        self.t     = 0

    def update(self, d_m: float) -> bool:
        """
        Update S(t) with new observation d_M(t).
        Returns True if S(t) ≥ h (drift alert).
        """
        self.S = max(0.0, self.S + d_m - self.mu0 - self.kappa)
        self.t += 1
        return self.S >= self.h

    def reset(self) -> None:
        self.S = 0.0

    @classmethod
    def from_historical(
        cls,
        distances: np.ndarray,
        delta: float = CUSUM_DELTA,
        h: float     = CUSUM_H,
    ) -> "CUSUMDetector":
        """Estimate mu0 from a sample of in-control Mahalanobis distances."""
        mu0 = float(distances.mean())
        logger.info("CUSUM mu0 estimated: %.4f (n=%d)", mu0, len(distances))
        return cls(mu0=mu0, delta=delta, h=h)


# ── Alert ─────────────────────────────────────────────────────────────────────

@dataclass
class DriftAlert:
    timestamp: float
    tick: int
    feature_vector: list[float]
    assigned_regime: int
    is_uncharted: bool
    mahalanobis_distance: float
    cusum_statistic: float
    recommended_config: dict
    justification: str


def build_justification(
    fv: FeatureVector,
    regime: RegimeEntry,
    d_m: float,
    S: float,
) -> str:
    """Generate a natural-language justification for the alert."""
    ranges  = regime.feature_ranges
    anomalies = []

    if fv.R_sec > ranges["R_sec"]["max"]:
        anomalies.append(f"R_sec={fv.R_sec:.3f} exceeds regime max "
                         f"{ranges['R_sec']['max']:.3f}")
    if fv.U_res > ranges["U_res"]["max"]:
        anomalies.append(f"U_res={fv.U_res:.3f} exceeds regime max "
                         f"{ranges['U_res']['max']:.3f}")
    if fv.F_rate > ranges["F_rate"]["max"]:
        anomalies.append(f"F_rate={fv.F_rate:.3f} exceeds regime max "
                         f"{ranges['F_rate']['max']:.3f}")

    anomaly_str = "; ".join(anomalies) if anomalies else "multivariate drift"
    active = [
        pid for pid, val in regime.recommended_config.items() if val > 0
    ]
    return (
        f"Sustained drift detected (d_M={d_m:.3f}, S={S:.3f}): "
        f"{anomaly_str}. "
        f"Current regime R{regime.regime_id} recommends activating "
        f"{active}."
    )


def emit_alert(alert: DriftAlert, path: str = ALERT_LOG_PATH) -> None:
    line = json.dumps(asdict(alert))
    with open(path, "a") as f:
        f.write(line + "\n")
    logger.warning("DRIFT ALERT  %s", alert.justification)


# ── Monitor loop ──────────────────────────────────────────────────────────────

class DriftMonitor:
    """
    Main Layer 2 loop: collect → assign → CUSUM → alert.
    """

    def __init__(
        self,
        rpt_path:   str = RPT_PATH,
        alert_path: str = ALERT_LOG_PATH,
        tick:       float = MONITORING_TICK_SECONDS,
    ):
        self.regimes, self.scaler = load_rpt(rpt_path)
        self.collector  = FeatureCollector(scaler=self.scaler)
        self.alert_path = alert_path
        self.tick       = tick
        self.cusum      = CUSUMDetector(mu0=0.3)   # override with from_historical
        self.t          = 0

    def _recommend(self, r_sec: float, f_rate: float) -> dict:
        """
        Compute the best config for the *current* observed R_sec / F_rate.
        Called at alert time so the recommendation reflects live pressure,
        not the historical regime mean (which may be 0 on benign-only data).
        """
        candidates = enumerate_binary_configurations()
        def phi(cfg):
            return (
                WEIGHT_SECURITY * cfg.protection_score() * r_sec
                - WEIGHT_LATENCY * cfg.cost_latency()
                - WEIGHT_RELIABILITY * f_rate
            )
        best = max(candidates, key=phi)
        return {pid: p.activation for pid, p in best.primitives.items()}

    def warm_up(self, n: int = 50) -> None:
        """
        Collect n in-control observations to estimate mu0 for CUSUM.
        Call this before run() if historical distances are not available.
        """
        logger.info("Warming up CUSUM with %d observations…", n)
        distances = []
        for _ in range(n):
            f = self.collector.collect()
            if f is not None:
                regime, d_m, _ = assign_regime(f, self.regimes)
                distances.append(d_m)
            time.sleep(self.tick)
        if distances:
            self.cusum = CUSUMDetector.from_historical(np.array(distances))

    def step(self) -> DriftAlert | None:
        """Execute one monitoring tick. Returns an alert or None."""
        f_norm = self.collector.collect()
        fv_raw = self.collector.collect_raw()

        if f_norm is None or fv_raw is None:
            logger.warning("Tick %d: feature collection failed", self.t)
            self.t += 1
            return None

        regime, d_m, is_uncharted = assign_regime(f_norm, self.regimes)
        alert_triggered = self.cusum.update(d_m)

        logger.info(
            "Tick %d | R%s | d_M=%.4f | S=%.4f | uncharted=%s",
            self.t,
            regime.regime_id if regime else "?",
            d_m, self.cusum.S, is_uncharted,
        )

        if alert_triggered or is_uncharted:
            rec = self._recommend(fv_raw.R_sec, fv_raw.F_rate)
            alert = DriftAlert(
                timestamp            = time.time(),
                tick                 = self.t,
                feature_vector       = fv_raw.to_array().tolist(),
                assigned_regime      = regime.regime_id if regime else -1,
                is_uncharted         = is_uncharted,
                mahalanobis_distance = d_m,
                cusum_statistic      = self.cusum.S,
                recommended_config   = rec,
                justification        = build_justification(
                    fv_raw, regime, d_m, self.cusum.S
                ) if regime else "Uncharted region — no matching regime.",
            )
            emit_alert(alert, self.alert_path)
            self.cusum.reset()   # reset after alert
            self.t += 1
            return alert

        self.t += 1
        return None

    def run(self) -> None:
        """Blocking monitoring loop. Ctrl-C to stop."""
        logger.info("Layer 2 monitor started (tick=%.1fs)", self.tick)
        try:
            while True:
                self.step()
                time.sleep(self.tick)
        except KeyboardInterrupt:
            logger.info("Monitor stopped.")


# ── CLI ───────────────────────────────────────────────────────────────────────

def main() -> None:
    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s [%(levelname)s] %(message)s")
    parser = argparse.ArgumentParser(
        description="TrustLens – Layer 2 Drift Monitor"
    )
    parser.add_argument("--rpt",   default=RPT_PATH,
                        help=f"RPT path (default: {RPT_PATH})")
    parser.add_argument("--alert", default=ALERT_LOG_PATH,
                        help=f"Alert log path (default: {ALERT_LOG_PATH})")
    parser.add_argument("--warmup", type=int, default=0,
                        help="Number of warm-up ticks to estimate mu0")
    args = parser.parse_args()

    monitor = DriftMonitor(rpt_path=args.rpt, alert_path=args.alert)
    if args.warmup > 0:
        monitor.warm_up(args.warmup)
    monitor.run()


if __name__ == "__main__":
    main()
