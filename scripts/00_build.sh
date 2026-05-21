#!/bin/bash
set -e
echo "=== Building ms-user ==="
cd ~/trustlens/testbed/ms-user && mvn clean package -DskipTests -q
echo "=== Building ms-location ==="
cd ~/trustlens/testbed/ms-location && mvn clean package -DskipTests -q
echo "=== Building ms-proximity-detection ==="
cd ~/trustlens/testbed/ms-proximity-detection && mvn clean package -DskipTests -q
echo "=== Building ms-notification ==="
cd ~/trustlens/testbed/ms-notification && mvn clean package -DskipTests -q
echo "=== Build complete ==="
