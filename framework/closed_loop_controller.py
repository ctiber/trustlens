#!/usr/bin/env python3
"""
TrustLens — closed-loop operator simulator (experiment harness, not part of
the framework proper).

Follows Layer 2 outputs and applies the recommended postures to the running
testbed, emulating an operator who accepts every recommendation:

  * Escalation: on a drift alert, the posture s*(R) recommended by the RPT for
    the assigned context R is applied after an operator delay D, if it is
    heavier than the active posture.
  * Downgrade ("no silent downgrade"): only after K consecutive ticks assigned
    to contexts recommending the same lighter posture, with no alert in
    between, and again after the operator delay D.

Postures are applied through the /zt/posture endpoint of every service (p2,
p5) and by hot-reloading the gateway on the mTLS or plain upstreams (p3).
p4 is kept on (it belongs to every recommended posture).

Usage:
  python closed_loop_controller.py --rpt rpt.json --monitor-log monitor.log \
      --alerts alerts.jsonl --out posture_log.csv --initial 1,0,0,1,0
"""
from __future__ import annotations

import argparse
import csv
import json
import re
import subprocess
import time
from collections import deque
from pathlib import Path

import requests

SERVICES = ["http://localhost:8081", "http://localhost:8082",
            "http://localhost:8083", "http://localhost:8084"]
TESTBED = Path.home() / "trustlens" / "testbed"
TICK_RE = re.compile(r"Tick (\d+) \| R(\S+) \|")


def heavier(a, b) -> bool:
    """True if posture a activates something that b does not (or more)."""
    return any(x > y + 1e-9 for x, y in zip(a, b))


class Controller:
    def __init__(self, args):
        rpt = json.loads(Path(args.rpt).read_text())
        self.s_star = {str(r["regime_id"]): tuple(float(r["recommended_config"][p])
                                                  for p in ["p1", "p2", "p3", "p4", "p5"])
                       for r in rpt["regimes"]}
        self.delay, self.k = args.delay, args.downgrade_ticks
        self.current = tuple(float(x) for x in args.initial.split(","))
        self.pending = None           # (apply_at, posture, reason)
        self.recent = deque(maxlen=self.k)
        self.last_alert = 0.0
        self.out = open(args.out, "w", newline="")
        self.log = csv.writer(self.out)
        self.log.writerow(["epoch", "event", "posture", "detail"])
        self.record("initial", self.current, "")

    def record(self, event, posture, detail):
        self.log.writerow([f"{time.time():.1f}", event, list(posture), detail])
        self.out.flush()
        print(f"[controller] {time.strftime('%H:%M:%S')} {event} {list(posture)} {detail}", flush=True)

    # ── actuation ────────────────────────────────────────────────────────────
    def apply(self, posture, reason):
        p2, p3, p5 = posture[1] > 0, posture[2] > 0, posture[4]
        body = {"ac4a": p2, "mtls": p3, "ra": p5 > 0, "raLevel": p5 if p5 > 0 else 1.0}
        for svc in SERVICES:
            try:
                requests.post(f"{svc}/zt/posture", json=body, timeout=3)
            except requests.RequestException as ex:
                print(f"[controller] {svc} unreachable: {ex}", flush=True)
        if p3 != (self.current[2] > 0):
            src = TESTBED / "gateway" / ("nginx-dual-on.conf" if p3 else "nginx-dual-off.conf")
            (TESTBED / "gateway" / "active.conf").write_text(src.read_text())
            subprocess.run(["docker", "compose", "-f", str(TESTBED / "docker-compose.zt.yml"),
                            "-p", "testbed", "exec", "-T", "api-gateway", "nginx", "-s", "reload"],
                           capture_output=True)
        self.current = posture
        self.record("applied", posture, reason)

    # ── decisions ────────────────────────────────────────────────────────────
    def on_alert(self, alert):
        self.last_alert = time.time()
        regime = str(alert.get("assigned_regime"))
        target = self.s_star.get(regime)
        if target is None:
            return
        if self.pending and not heavier(self.pending[1], self.current):
            self.record("downgrade-cancelled", self.pending[1], f"alert tick {alert['tick']}")
            self.pending = None
        if heavier(target, self.current) and (self.pending is None or heavier(target, self.pending[1])):
            self.pending = (time.time() + self.delay, target, f"escalation: alert tick {alert['tick']} R{regime}")
            self.record("escalation-scheduled", target, f"R{regime}")

    def on_tick(self, regime):
        self.recent.append(regime)
        if self.pending or len(self.recent) < self.k:
            return
        targets = {self.s_star.get(r) for r in self.recent}
        if len(targets) != 1 or None in targets:
            return
        target = targets.pop()
        quiet = time.time() - self.last_alert > self.k * 5
        if quiet and target != self.current and heavier(self.current, target) \
                and not heavier(target, self.current):
            self.pending = (time.time() + self.delay, target,
                            f"downgrade validated: {self.k} ticks in R{regime}")
            self.record("downgrade-scheduled", target, f"R{regime}")

    def tick(self):
        if self.pending and time.time() >= self.pending[0]:
            _, posture, reason = self.pending
            self.pending = None
            if posture != self.current:
                self.apply(posture, reason)


def follow(path: Path):
    """Yield new lines appended to a file (tail -f)."""
    while not path.exists():
        time.sleep(0.5)
        yield None
    with path.open() as fh:
        fh.seek(0, 2)
        while True:
            line = fh.readline()
            yield line if line else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rpt", required=True)
    ap.add_argument("--monitor-log", required=True)
    ap.add_argument("--alerts", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--initial", default="1,0,0,1,0")
    ap.add_argument("--delay", type=float, default=30.0, help="operator delay (s)")
    ap.add_argument("--downgrade-ticks", type=int, default=24)
    args = ap.parse_args()

    ctl = Controller(args)
    ticks, alerts = follow(Path(args.monitor_log)), follow(Path(args.alerts))
    while True:
        busy = False
        for _ in range(50):
            line = next(alerts)
            if not line:
                break
            busy = True
            try:
                ctl.on_alert(json.loads(line))
            except json.JSONDecodeError:
                pass
        for _ in range(50):
            line = next(ticks)
            if not line:
                break
            busy = True
            m = TICK_RE.search(line)
            if m:
                ctl.on_tick(m.group(2))
        ctl.tick()
        if not busy:
            time.sleep(0.5)


if __name__ == "__main__":
    main()
