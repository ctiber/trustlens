"""
TrustLens – Security Primitive Space
======================================
Defines the primitive catalogue and the additive cost model
(Equation 5 in the paper).
"""

from __future__ import annotations
from dataclasses import dataclass, field
from typing import Dict
import numpy as np

from config import PRIMITIVES, PRIMITIVE_COSTS, PRIMITIVE_DEPENDENCIES


@dataclass
class Primitive:
    id: str
    name: str
    kind: str                    # "binary" | "graduated"
    activation: float = 0.0     # a_i ∈ {0,1} or [0,1]

    def validate(self) -> None:
        if self.kind == "binary" and self.activation not in (0.0, 1.0):
            raise ValueError(
                f"Primitive {self.id} is binary but activation={self.activation}"
            )
        if not 0.0 <= self.activation <= 1.0:
            raise ValueError(
                f"Activation for {self.id} must be in [0,1], got {self.activation}"
            )


class SecurityConfiguration:
    """
    A vector s = (a_1, ..., a_n) over the primitive catalogue.
    Supports additive cost estimation and human-readable display.
    """

    def __init__(self, activations: Dict[str, float]):
        """
        Parameters
        ----------
        activations : dict mapping primitive id → activation level
                      e.g. {"p1": 1, "p2": 1, "p3": 0, "p4": 0.5, "p5": 0, "p6": 0}
        """
        self.primitives: Dict[str, Primitive] = {}
        catalogue = {p[0]: p for p in PRIMITIVES}

        for pid, (pid_, name, kind, _) in catalogue.items():
            a = activations.get(pid, 0.0)
            prim = Primitive(id=pid, name=name, kind=kind, activation=a)
            prim.validate()
            self.primitives[pid] = prim

    # ── Cost model (Eq. 5) ────────────────────────────────────────────────────

    def cost_latency(self) -> float:
        """Estimated marginal latency overhead Cost_T(s) in seconds."""
        return sum(
            self.primitives[pid].activation * PRIMITIVE_COSTS[pid]["delta_T"]
            for pid in self.primitives
        )

    def effective_activation(self, pid: str) -> float:
        """
        Activation that actually protects, given the primitive dependencies
        (PRIMITIVE_DEPENDENCIES): e.g. rotating the JWT key (p4) protects
        nothing unless some service verifies the tokens (p2).
        """
        a = self.primitives[pid].activation
        for dep in PRIMITIVE_DEPENDENCIES.get(pid, ()):
            a = min(a, self.primitives[dep].activation)
        return a

    def protection_score(self) -> float:
        """
        Security activation level m(s): mean effective activation, 0.0 (none)
        → 1.0 (all). Used in Φ so that configs with more (effectively) active
        primitives score higher when R_sec is elevated (Eq. 6).
        """
        if not self.primitives:
            return 0.0
        return sum(self.effective_activation(pid) for pid in self.primitives) / len(self.primitives)

    def cost_resources(self) -> float:
        """Estimated marginal resource overhead Cost_U(s) in [0,1]."""
        return sum(
            self.primitives[pid].activation * PRIMITIVE_COSTS[pid]["delta_U"]
            for pid in self.primitives
        )

    # ── Utilities ─────────────────────────────────────────────────────────────

    def to_vector(self) -> np.ndarray:
        """Return activation vector as numpy array (ordered by primitive id)."""
        return np.array([
            self.primitives[pid].activation
            for pid in sorted(self.primitives)
        ])

    def active_primitives(self) -> list[str]:
        """Return names of active (a_i > 0) primitives."""
        return [
            p.name for p in self.primitives.values() if p.activation > 0
        ]

    def __repr__(self) -> str:
        vec = ", ".join(
            f"{pid}={p.activation}"
            for pid, p in self.primitives.items()
        )
        return f"SecurityConfiguration({vec})"


# ── Reference configurations ──────────────────────────────────────────────────

def make_v1() -> SecurityConfiguration:
    from config import CONFIG_V1
    return SecurityConfiguration(CONFIG_V1)

def make_v2() -> SecurityConfiguration:
    from config import CONFIG_V2
    return SecurityConfiguration(CONFIG_V2)

def make_v3() -> SecurityConfiguration:
    from config import CONFIG_V3
    return SecurityConfiguration(CONFIG_V3)


# ── Candidate configuration generator ────────────────────────────────────────

def enumerate_binary_configurations() -> list[SecurityConfiguration]:
    """
    Enumerate all 2^n configurations for a fully binary catalogue.
    Feasible for n ≤ 10; use greedy_configurations() for larger catalogues.
    """
    import itertools
    binary_ids = [p[0] for p in PRIMITIVES if p[2] == "binary"]
    graduated_ids = [p[0] for p in PRIMITIVES if p[2] == "graduated"]

    configs = []
    for bits in itertools.product([0, 1], repeat=len(binary_ids)):
        activations = dict(zip(binary_ids, bits))
        # graduated primitives default to 0 in exhaustive search;
        # extend with a discrete grid if needed
        for gid in graduated_ids:
            activations[gid] = 0.0
        configs.append(SecurityConfiguration(activations))
    return configs


def greedy_configurations(
    regime_r_sec: float,
    regime_f_rate: float,
    w_s: float = 0.6,
    w_l: float = 0.2,
    w_r: float = 0.2,
    max_primitives: int | None = None,
) -> SecurityConfiguration:
    """
    Greedy activation: iteratively enable the primitive that maximises
    the marginal improvement in Φ until no improvement is possible or
    max_primitives is reached.

    Parameters
    ----------
    regime_r_sec  : observed R_sec in the target regime
    regime_f_rate : observed F_rate in the target regime
    w_s, w_l, w_r : scoring weights
    max_primitives: optional cap on number of active primitives
    """
    activations = {p[0]: 0.0 for p in PRIMITIVES}
    active_count = 0

    def phi(a: Dict[str, float]) -> float:
        cfg = SecurityConfiguration(a)
        return (
            w_s * cfg.protection_score() * regime_r_sec
            - w_l * cfg.cost_latency()
            - w_r * regime_f_rate
        )

    current_score = phi(activations)

    while True:
        best_gain = 0.0
        best_pid = None

        for pid, _, kind, _ in PRIMITIVES:
            if activations[pid] > 0:
                continue
            candidate = dict(activations)
            candidate[pid] = 1.0 if kind == "binary" else 0.5
            gain = phi(candidate) - current_score
            if gain > best_gain:
                best_gain = gain
                best_pid = pid

        if best_pid is None:
            break
        kind_map = {p[0]: p[2] for p in PRIMITIVES}
        activations[best_pid] = 1.0 if kind_map[best_pid] == "binary" else 0.5
        current_score += best_gain
        active_count += 1
        if max_primitives and active_count >= max_primitives:
            break

    return SecurityConfiguration(activations)


# ── Candidate set and recommendation (Eq. 6–7) ───────────────────────────────

GRADUATED_LEVELS = (0.0, 0.5, 1.0)


def enumerate_candidate_configurations(
    levels: tuple[float, ...] = GRADUATED_LEVELS,
) -> list[SecurityConfiguration]:
    """
    Candidate set S_d of the paper: every on/off combination of the binary
    primitives (2^3 = 8) times every discretised level of the graduated
    primitives (3 × 3 = 9) — 72 postures.
    """
    import itertools
    binary_ids = [p[0] for p in PRIMITIVES if p[2] == "binary"]
    graduated_ids = [p[0] for p in PRIMITIVES if p[2] == "graduated"]
    configs = []
    for bits in itertools.product([0, 1], repeat=len(binary_ids)):
        for lv in itertools.product(levels, repeat=len(graduated_ids)):
            activations = dict(zip(binary_ids, bits))
            activations.update(dict(zip(graduated_ids, lv)))
            configs.append(SecurityConfiguration(activations))
    return configs


def max_cost_latency() -> float:
    """Cost_T of the posture with every primitive at full activation."""
    return sum(max(c["delta_T"], 0.0) for c in PRIMITIVE_COSTS.values()) or 1.0


def phi(
    cfg: SecurityConfiguration,
    r_sec: float,
    f_rate: float,
    w_s: float,
    w_l: float,
    w_r: float,
) -> float:
    """
    Scoring function Φ(s, R_i) (Eq. 6). r_sec and f_rate are min-max
    normalised feature values; Cost_T is normalised by the cost of the
    all-active posture so that the three terms share the [0, 1] scale.
    """
    return (
        w_s * cfg.protection_score() * r_sec
        - w_l * cfg.cost_latency() / max_cost_latency()
        - w_r * f_rate
    )


def recommend(
    r_sec: float,
    f_rate: float,
    w_s: float,
    w_l: float,
    w_r: float,
) -> tuple[SecurityConfiguration, float]:
    """
    s* = argmax_{s in S_d} Φ(s, R_i) (Eq. 7). Ties are broken towards the
    cheaper posture, then towards the more protective one (so that a
    zero-cost primitive such as the perimeter p1 is never switched off).
    """
    best = max(
        enumerate_candidate_configurations(),
        key=lambda c: (round(phi(c, r_sec, f_rate, w_s, w_l, w_r), 12),
                       -round(c.cost_latency(), 12),
                       c.protection_score()),
    )
    return best, phi(best, r_sec, f_rate, w_s, w_l, w_r)
