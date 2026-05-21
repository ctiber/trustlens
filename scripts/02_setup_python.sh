#!/bin/bash
set -e
cd ~/trustlens/framework
python3 -m venv .venv
source .venv/bin/activate
pip install --upgrade pip -q
pip install -r requirements.txt -q
echo "=== Python environment ready ==="
