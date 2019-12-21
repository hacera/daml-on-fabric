#!/usr/bin/env bash

set -euo pipefail

# NOTE: This file is used by `make it` in a context where the example ledger
# server has already been built. It is not intended to be used directly.

echo "Detecting current DAML SDK version used in the SBT build..."
sdkVersion=$(sbt --error 'set showSuccess := false'  printSdkVersion)
bintrayTestToolPath="https://bintray.com/api/v1/content/digitalassetsdk/DigitalAssetSDK/com/daml/ledger/testtool/ledger-api-test-tool/"
# sdkVersion=$(cat build.sbt| egrep -o "sdkVersion.*=.*\".*\"" | perl -pe 's|sdkVersion.*?=.*?"(.*?)"|\1|')
echo "Detected SDK version is $sdkVersion"

echo "Downloading DAML Integration kit Ledger API Test Tool version ${sdkVersion}..."
curl -L "${bintrayTestToolPath}${sdkVersion}/ledger-api-test-tool-${sdkVersion}.jar?bt_package=sdk-components" \
     -o src/test/fixture/ledger-api-test-tool.jar

echo "Extracting the .dar file to load in DAML-on-Fabric server..."
cd src/test/fixture && java -jar ledger-api-test-tool.jar --extract || true # mask incorrect error code of the tool: https://github.com/digital-asset/daml/pull/889

echo "Building CI Docker image"
./build_ci.sh

function compress_dir() {
  tar  -C $1 -czvvf - . | base64
}

# This is specifically for CircleCI:
# It does not allow us to attach volume from host machine, like usually configured for Fabric.
# Thus we just send whole configuration directory this way.

echo "Compressing MSP directory for Fabric..."
export CONFIGTX="$(compress_dir ./data/)"

echo "Launching Fabric network and DAML-on-Fabric server"
export DOCKER_COMPOSE_FILE=docker-compose-ci.yaml
export DOCKER_NETWORK=daml-on-fabric_ci
./fabric.sh down
./fabric.sh updetached
#cd ../../../

echo "Giving time for everything to initialize"
sleep 120s

echo "Launching the test tool..."
export TEST_COMMAND="/usr/local/openjdk-8/bin/java -jar ledger-api-test-tool.jar localhost:12222 --include=SemanticTests --timeout-scale-factor 3.5"
docker exec -it damlonfabric_daml_on_fabric_2 $TEST_COMMAND
echo "Test tool run is complete."
