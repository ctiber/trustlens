"""
TrustLens – Layer 1: Operational Regime Profiling
===================================================
Offline script that:
  1. Loads (or collects) a historical telemetry dataset D
  2. Normalises features with MinMaxScaler
  3. Identifies regimes via DBSCAN + k-means refinement
  4. Scores each candidate configuration with Φ
  5. Writes the Regime Profile Table (RPT) to rpt.json

Usage:
    python layer1_profiling.py --data history.csv
    python layer1_profiling.py --collect 500   # collect 500 live samples first
"""

from __future__ import annotations
import argparse
import json
import logging
import warnings
from pathlib import Path
from itertools import product as iproduct

import numpy as np
import pandas as pd
from sklearn.cluster import DBSCAN, KMeans
from sklearn.preprocessing import MinMaxScaler

from config import (
    DBSCAN_EPS, DBSCAN_MIN_SAMPLES,
    KMEANS_MAX_ITER, KMEANS_N_INIT,
    WEIGHT_SECURITY, WEIGHT_LATENCY, WEIGHT_RELIABILITY,
    FEATURE_NAMES, RPT_PATH,
    PRIMITIVES, PRIMITIVE_COSTS, CONFIG_V1, CONFIG_V2, CONFIG_V3,
)
from primitives import SecurityConfiguration, enumerate_binary_configurations

logger = logging.getLogger(__name__)
warnings.filterwarnings("ignore", category=FutureWarning)


# ── Regime ────────────────────────────────────────────────────────────────────

class Regime:
    """Represents one identified operational regime R_i."""

    def __init__(
        self,
        regime_id: int,
        centroid: np.ndarray,
        covariance: np.ndarray,
        members: np.ndarray,
    ):
        self.id         = regime_id
        self.centroid   = centroid          # μ_i  shape (4,)
        self.covariance = covariance        # Σ_i  shape (4, 4)
        self.cov_inv    = np.linalg.pinv(covariance)
        self.members    = members           # raw feature rows belonging to R_i
        self.feature_ranges: dict = {}      # min/max per feature
        self.recommended_config: dict = {}  # s*(R_i)
        self.score: float = 0.0

        self._compute_ranges()

    def _compute_ranges(self) -> None:
        for j, name in enumerate(FEATURE_NAMES):
            col = self.members[:, j]
            self.feature_ranges[name] = {
                "min": float(col.min()),
                "max": float(col.max()),
                "mean": float(col.mean()),
            }

    def mahalanobis(self, f: np.ndarray) -> float:
        diff = f - self.centroid
        return float(np.sqrt(diff @ self.cov_inv @ diff))

    def to_dict(self) -> dict:
        return {
            "regime_id": self.id,
            "centroid": self.centroid.tolist(),
            "feature_ranges": self.feature_ranges,
            "recommended_config": self.recommended_config,
            "score": self.score,
        }


# ── Regime Profiler ───────────────────────────────────────────────────────────

class RegimeProfiler:
    """
    Implements Layer 1: DBSCAN → k-means → scoring Φ → RPT.
    """

    def __init__(
        self,
        w_s: float = WEIGHT_SECURITY,
        w_l: float = WEIGHT_LATENCY,
        w_r: float = WEIGHT_RELIABILITY,
    ):
        self.w_s    = w_s
        self.w_l    = w_l
        self.w_r    = w_r
        self.scaler = MinMaxScaler()
        self.regimes: list[Regime] = []

    # ── Step 1: Normalise ─────────────────────────────────────────────────────

    def fit_scaler(self, D: np.ndarray) -> np.ndarray:
        return self.scaler.fit_transform(D)

    # ── Step 2: Clustering ────────────────────────────────────────────────────

    def cluster(self, D_norm: np.ndarray) -> np.ndarray:
        """
        Two-step clustering:
          1. DBSCAN → identifies K and removes noise
          2. k-means seeded with DBSCAN centroids → refines boundaries
        Returns array of cluster labels (noise points labelled -1).
        """
        # DBSCAN pass
        db = DBSCAN(eps=DBSCAN_EPS, min_samples=DBSCAN_MIN_SAMPLES)
        db_labels = db.fit_predict(D_norm)
        n_clusters = len(set(db_labels) - {-1})

        if n_clusters == 0:
            logger.warning("DBSCAN found no clusters. Falling back to k=2.")
            n_clusters = 2

        logger.info("DBSCAN: %d clusters, %d noise points",
                    n_clusters, np.sum(db_labels == -1))

        # Compute DBSCAN centroids as k-means seeds
        seeds = np.array([
            D_norm[db_labels == k].mean(axis=0)
            for k in range(n_clusters)
        ])

        # k-means refinement
        km = KMeans(
            n_clusters=n_clusters,
            init=seeds,
            n_init=1,
            max_iter=KMEANS_MAX_ITER,
        )
        km_labels = km.fit_predict(D_norm)
        logger.info("k-means inertia: %.4f", km.inertia_)

        return km_labels

    # ── Step 3: Build regime objects ─────────────────────────────────────────

    def build_regimes(
        self, D_norm: np.ndarray, labels: np.ndarray
    ) -> list[Regime]:
        regimes = []
        for k in sorted(set(labels)):
            members = D_norm[labels == k]
            centroid = members.mean(axis=0)
            covariance = np.cov(members.T) + np.eye(members.shape[1]) * 1e-6
            regimes.append(Regime(int(k), centroid, covariance, members))
        self.regimes = regimes
        return regimes

    # ── Step 4: Score configurations ─────────────────────────────────────────

    def phi(
        self,
        config: SecurityConfiguration,
        r_sec_regime: float,
        f_rate_regime: float,
    ) -> float:
        """
        Scoring function Φ(s, R_i) — Equation (6) in the paper.
        r_sec_regime and f_rate_regime are the mean observed values
        for regime R_i (MinMax-normalised, in [0,1]).
        Cost_T(s) is normalised by the maximum possible cost (V3 config)
        so all three terms share the same [0,1] scale.
        """
        max_cost = sum(PRIMITIVE_COSTS[p]["delta_T"] for p in PRIMITIVE_COSTS)
        normalized_cost = config.cost_latency() / max_cost  # in [0,1]
        return (
            self.w_s * config.protection_score() * r_sec_regime
            - self.w_l * normalized_cost
            - self.w_r * f_rate_regime
        )

    def recommend(self, regime: Regime) -> SecurityConfiguration:
        """
        Exhaustive search over binary candidate configurations.
        Returns s*(R_i) = argmax_s Φ(s, R_i).
        """
        r_sec  = regime.feature_ranges["R_sec"]["mean"]
        f_rate = regime.feature_ranges["F_rate"]["mean"]

        candidates = enumerate_binary_configurations()
        best_config = candidates[0]
        best_score  = self.phi(candidates[0], r_sec, f_rate)

        for cfg in candidates[1:]:
            score = self.phi(cfg, r_sec, f_rate)
            if score > best_score:
                best_score  = score
                best_config = cfg

        regime.recommended_config = {
            pid: p.activation
            for pid, p in best_config.primitives.items()
        }
        regime.score = best_score
        logger.info(
            "Regime %d → s*=%s  Φ=%.4f",
            regime.id, best_config.to_vector(), best_score,
        )
        return best_config

    # ── Step 5: Write RPT ─────────────────────────────────────────────────────

    def write_rpt(self, path: str = RPT_PATH) -> None:
        rpt = {
            "scaler_min": self.scaler.data_min_.tolist(),
            "scaler_max": self.scaler.data_max_.tolist(),
            "regimes": [r.to_dict() for r in self.regimes],
        }
        Path(path).write_text(json.dumps(rpt, indent=2))
        logger.info("RPT written to %s  (%d regimes)", path, len(self.regimes))

    # ── Full pipeline ─────────────────────────────────────────────────────────

    def run(self, D: np.ndarray, rpt_path: str = RPT_PATH) -> list[Regime]:
        logger.info("Layer 1 — dataset shape: %s", D.shape)
        D_norm  = self.fit_scaler(D)
        labels  = self.cluster(D_norm)
        regimes = self.build_regimes(D_norm, labels)
        for regime in regimes:
            self.recommend(regime)
        self.write_rpt(rpt_path)
        return regimes


# ── CLI ───────────────────────────────────────────────────────────────────────

def load_csv(path: str) -> np.ndarray:
    df = pd.read_csv(path, usecols=FEATURE_NAMES)
    logger.info("Loaded %d rows from %s", len(df), path)
    return df[FEATURE_NAMES].values


def collect_live(n: int) -> np.ndarray:
    from features import FeatureCollector
    logger.info("Collecting %d live samples from Prometheus…", n)
    return FeatureCollector().collect_batch(n)


def main() -> None:
    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s [%(levelname)s] %(message)s")
    parser = argparse.ArgumentParser(description="TrustLens – Layer 1 Profiling")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--data",    metavar="CSV",  help="Path to historical CSV")
    group.add_argument("--collect", metavar="N",
                       type=int, help="Collect N live samples from Prometheus")
    parser.add_argument("--rpt", default=RPT_PATH,
                        help=f"Output RPT path (default: {RPT_PATH})")
    args = parser.parse_args()

    D = load_csv(args.data) if args.data else collect_live(args.collect)

    profiler = RegimeProfiler()
    regimes  = profiler.run(D, args.rpt)

    print(f"\n{'─'*60}")
    print(f"  Regime Profile Table — {len(regimes)} regimes identified")
    print(f"{'─'*60}")
    for r in regimes:
        print(f"  R{r.id}  centroid={np.round(r.centroid, 3)}"
              f"  s*={list(r.recommended_config.values())}"
              f"  Φ={r.score:.4f}")
    print(f"{'─'*60}")
    print(f"  RPT saved to: {args.rpt}\n")


if __name__ == "__main__":
    main()
