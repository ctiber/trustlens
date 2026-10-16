#!/bin/bash
# Observation-floor cost (5 isolation runs) followed by the closed-loop campaign.
set -e
cd "$(dirname "$0")"
echo "##### [$(date)] isolation of the observation floor"
RUNS=5 CONFIGS="iso-floor" ./03_isolate_primitives_zt.sh
echo "##### [$(date)] closed loop"
./10_closed_loop_zt.sh
echo "##### [$(date)] all done"
