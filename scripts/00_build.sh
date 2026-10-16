#!/bin/bash
set -e
echo "=== Building zt-common (shared ZT primitives) ==="
cd ~/trustlens/testbed/zt-common && mvn clean install -DskipTests -q
echo "=== Building ms-user ==="
cd ~/trustlens/testbed/ms-user && mvn clean package -DskipTests -q
echo "=== Building ms-location ==="
cd ~/trustlens/testbed/ms-location && mvn clean package -DskipTests -q
echo "=== Building ms-proximity-detection ==="
cd ~/trustlens/testbed/ms-proximity-detection && mvn clean package -DskipTests -q
echo "=== Building ms-notification ==="
cd ~/trustlens/testbed/ms-notification && mvn clean package -DskipTests -q
echo "=== Build complete ==="
echo "=== Installing risk-analysis dependencies (copied into its image) ==="
cd ~/trustlens/testbed/risk-analysis && npm install --omit=dev --no-audit --no-fund
echo "=== Building Docker images ==="
cd ~/trustlens/testbed && docker compose -f docker-compose.zt.yml -p testbed --profile sr --profile ra build
echo "=== Images built ==="
