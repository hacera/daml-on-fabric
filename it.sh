#!/usr/bin/env bash

set -euo pipefail

# NOTE: This file is used by `make it` in a context where the example ledger
# server has already been built. It is not intended to be used directly.

echo "Detecting current DAML SDK version used in the SBT build..."
sdkVersion=$(sbt --error 'set showSuccess := false'  printSdkVersion)
# sdkVersion=$(cat build.sbt| egrep -o "sdkVersion.*=.*\".*\"" | perl -pe 's|sdkVersion.*?=.*?"(.*?)"|\1|')
echo "Detected SDK version is $sdkVersion"

echo "Downloading DAML Integration kit Ledger API Test Tool version ${sdkVersion}..."
curl -L "https://bintray.com/api/v1/content/digitalassetsdk/DigitalAssetSDK/com/daml/ledger/testtool/ledger-api-test-tool_2.12/${sdkVersion}/ledger-api-test-tool_2.12-${sdkVersion}.jar?bt_package=sdk-components" \
     -o target/ledger-api-test-tool.jar


echo "Extracting the .dar file to load in DAML-on-Fabric server..."
cd target && java -jar ledger-api-test-tool.jar --extract || true # mask incorrect error code of the tool: https://github.com/digital-asset/daml/pull/889
# back to prior working directory
cd ../

echo "Building CI Docker image"
cd src/test/fixture/
./build_ci.sh

echo "Launching Fabric network and DAML-on-Fabric server"
export DOCKER_COMPOSE_FILE=docker-compose-ci.yaml
export DOCKER_NETWORK=daml-on-fabric_ci
./fabric.sh down
./fabric.sh updetached
cd ../../../

echo "Giving time for everything to initialize"
sleep 90s

echo "Launching the test tool..."
docker logs damlonfabric_daml_on_fabric
docker exec -it damlonfabric_daml_on_fabric /bin/bash -c "java -jar ledger-api-test-tool.jar -h localhost -p 6865; exit $?"
echo "Test tool run is complete."
echo "Killing the network..."
./fabric.sh down
