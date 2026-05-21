#!/usr/bin/env python3
"""
export_prometheus.py — Stream feature vectors from Prometheus to CSV.

Designed to run concurrently with Gatling:
    python export_prometheus.py --interval 5 --output history.csv &
    PID=$!
    run_gatling ...
    kill $PID
"""
import argparse
import csv
import os
import signal
import sys
import time
import logging

sys.path.insert(0, os.path.dirname(__file__))
from features import FeatureCollector

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
)
log = logging.getLogger(__name__)

_running = True


def _handle_signal(signum, frame):
    global _running
    _running = False


signal.signal(signal.SIGTERM, _handle_signal)
signal.signal(signal.SIGINT, _handle_signal)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--window",   type=int, default=60,           help="PromQL window seconds")
    p.add_argument("--interval", type=int, default=5,            help="Polling interval seconds")
    p.add_argument("--samples",  type=int, default=0,            help="Max samples, 0=unlimited")
    p.add_argument("--output",   default="history.csv",          help="Output CSV path")
    args = p.parse_args()

    collector = FeatureCollector(window=args.window)
    n = 0

    with open(args.output, "w", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(["timestamp", "T_avg", "U_res", "F_rate", "R_sec"])

        while _running:
            if args.samples > 0 and n >= args.samples:
                break

            fv = collector.collect_raw()
            if fv is not None:
                writer.writerow([
                    fv.timestamp,
                    fv.T_avg,
                    fv.U_res,
                    fv.F_rate,
                    fv.R_sec,
                ])
                fh.flush()
                n += 1
                log.info("[%d] T_avg=%.4fs (%.1fms)  U_res=%.2f%%  F_rate=%.4f  R_sec=%.4f",
                         n, fv.T_avg, fv.T_avg * 1000, fv.U_res, fv.F_rate, fv.R_sec)

            time.sleep(args.interval)

    log.info("Exported %d samples to %s", n, args.output)


if __name__ == "__main__":
    main()
