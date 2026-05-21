"""
TrustLens – Feature Collector
================================
Pulls the four features f = (T_avg, U_res, F_rate, R_sec) from
Prometheus and returns a normalised feature vector.

Usage (standalone):
    python features.py          # prints current feature vector
"""

from __future__ import annotations
import time
import logging
from dataclasses import dataclass

import numpy as np
import requests

from config import (
    PROMETHEUS_URL,
    FEATURE_WINDOW_SECONDS,
    PROMQL_T_AVG,
    PROMQL_U_RES,
    PROMQL_F_RATE,
    PROMQL_R_SEC,
    FEATURE_NAMES,
)

logger = logging.getLogger(__name__)


# ── Raw feature vector ────────────────────────────────────────────────────────

@dataclass
class FeatureVector:
    T_avg:  float   # mean response time (ms)
    U_res:  float   # composite resource utilisation (normalised)
    F_rate: float   # failure rate [0, 1]
    R_sec:  float   # security pressure proxy [0, 1]
    timestamp: float = 0.0

    def to_array(self) -> np.ndarray:
        return np.array([self.T_avg, self.U_res, self.F_rate, self.R_sec],
                        dtype=float)

    def __repr__(self) -> str:
        return (
            f"FeatureVector(T_avg={self.T_avg:.4f}, U_res={self.U_res:.4f}, "
            f"F_rate={self.F_rate:.4f}, R_sec={self.R_sec:.4f})"
        )


# ── Prometheus client ─────────────────────────────────────────────────────────

class PrometheusClient:
    def __init__(self, base_url: str = PROMETHEUS_URL):
        self.base_url = base_url.rstrip("/")

    def query(self, promql: str) -> float | None:
        """Execute an instant PromQL query and return the scalar result."""
        try:
            resp = requests.get(
                f"{self.base_url}/api/v1/query",
                params={"query": promql},
                timeout=5,
            )
            resp.raise_for_status()
            data = resp.json()
            results = data.get("data", {}).get("result", [])
            if not results:
                logger.warning("Empty result for query: %s", promql)
                return None
            value = float(results[0]["value"][1])
            return value
        except Exception as exc:
            logger.error("Prometheus query failed (%s): %s", promql[:60], exc)
            return None


# ── Feature collection ────────────────────────────────────────────────────────

class FeatureCollector:
    """
    Collects and optionally normalises the feature vector from Prometheus.

    Parameters
    ----------
    scaler : fitted sklearn MinMaxScaler or None (returns raw values)
    window : sliding window in seconds for rate/histogram queries
    """

    def __init__(self, scaler=None, window: int = FEATURE_WINDOW_SECONDS):
        self.client  = PrometheusClient()
        self.scaler  = scaler
        self.window  = window

    def collect_raw(self) -> FeatureVector | None:
        """Pull raw (un-normalised) feature values from Prometheus."""
        w = self.window

        T_avg  = self.client.query(PROMQL_T_AVG.format(window=w))
        U_res  = self.client.query(PROMQL_U_RES.format(window=w))
        F_rate = self.client.query(PROMQL_F_RATE.format(window=w))
        R_sec  = self.client.query(PROMQL_R_SEC.format(window=w))

        # Replace None with 0.0 and warn
        values = {"T_avg": T_avg, "U_res": U_res,
                  "F_rate": F_rate, "R_sec": R_sec}
        for name, val in values.items():
            if val is None:
                logger.warning("Feature %s unavailable; defaulting to 0.0", name)
                values[name] = 0.0

        return FeatureVector(
            T_avg  = values["T_avg"],
            U_res  = values["U_res"],
            F_rate = values["F_rate"],
            R_sec  = values["R_sec"],
            timestamp = time.time(),
        )

    def collect(self) -> np.ndarray | None:
        """
        Return the normalised feature vector as a numpy array.
        Requires a fitted scaler; falls back to raw values if none.
        """
        fv = self.collect_raw()
        if fv is None:
            return None
        raw = fv.to_array().reshape(1, -1)
        if self.scaler is not None:
            return np.clip(self.scaler.transform(raw).flatten(), 0.0, 1.0)
        return raw.flatten()

    def collect_batch(
        self,
        n_samples: int,
        interval: float = FEATURE_WINDOW_SECONDS,
    ) -> np.ndarray:
        """
        Collect n_samples raw feature vectors at regular intervals.
        Used by Layer 1 to build the historical dataset D.

        Returns
        -------
        np.ndarray of shape (n_samples, 4)
        """
        samples = []
        for i in range(n_samples):
            fv = self.collect_raw()
            if fv is not None:
                samples.append(fv.to_array())
                logger.info("[%d/%d] %s", i + 1, n_samples, fv)
            else:
                logger.warning("[%d/%d] collection failed, skipping", i+1, n_samples)
            if i < n_samples - 1:
                time.sleep(interval)
        return np.array(samples)


# ── CLI ───────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    collector = FeatureCollector()
    fv = collector.collect_raw()
    if fv:
        print(fv)
        print("Array:", fv.to_array())
    else:
        print("Could not collect features — is Prometheus running?")
