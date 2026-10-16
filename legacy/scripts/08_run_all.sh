#!/bin/bash
set -e
BASE=~/trustlens/scripts
bash "$BASE/00_build.sh"
bash "$BASE/01_gen_certs.sh"
bash "$BASE/02_setup_python.sh"
bash "$BASE/04_phase1_profiling.sh"
bash "$BASE/05_phase2_injection.sh"
bash "$BASE/06_baselines.sh"
bash "$BASE/07_analyse.sh"
echo ""
echo "╔══════════════════════════════════════════╗"
echo "║  All experiments complete.               ║"
echo "║  Results: ~/trustlens/results/           ║"
echo "╚══════════════════════════════════════════╝"
