#!/usr/bin/env bash

set -euo pipefail

# NOTE: This file is used by `make it` in a context where the example ledger
# server has already been built. It is not intended to be used directly.
echo "switch to desired directory"
cd src/test/fixture

echo "Downloading ledger test tool"
./download_test_tool_extract_dars.sh

echo "Building CI Docker image"
./build_ci.sh

function compress_dir() {
  tar  -C $1 -czvvf - . | base64
}

# This is specifically for CircleCI:
# It does not allow us to attach volume from host machine, like usually configured for Fabric.
# Thus we just send whole configuration directory this way.

echo "Compressing MSP directory for Fabric..."
CONFIGTX="$(compress_dir ./data/)"
export CONFIGTX=$CONFIGTX

echo "Launching Fabric network and DAML-on-Fabric server"
export DOCKER_COMPOSE_FILE=docker-compose-ci.yaml
export DOCKER_NETWORK=daml-on-fabric_ci
./fabric.sh down
./fabric.sh updetached

echo "Giving time for everything to initialize"
sleep 90s

echo "Launching the test tool..."
export TEST_COMMAND="/usr/local/openjdk-8/bin/java -jar ledger-api-test-tool.jar localhost:12222 --include=SemanticTests --timeout-scale-factor 5"
docker exec -it damlonfabric_daml_on_fabric_2 $TEST_COMMAND
echo "Test tool run is complete."
