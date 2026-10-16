#!/bin/bash
# Full experimental campaign on the fine-grained ZT testbed (resumable).
set -e
cd "$(dirname "$0")"
bash "$HOME/trustlens/testbed/run-variant.sh" --down
echo "##### [$(date)] 03 isolation";        ./03_isolate_primitives_zt.sh
echo "##### [$(date)] 04f posture history";  ./04f_posture_history_zt.sh
echo "##### [$(date)] 04 phase 1";           ./04_phase1_profiling_zt.sh
echo "##### [$(date)] 05 phase 2";           ./05_phase2_injection_zt.sh
echo "##### [$(date)] campaign complete"
