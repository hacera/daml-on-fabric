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


echo "Extracting the .dar file to load in example server..."
cd target && java -jar ledger-api-test-tool.jar --extract || true # mask incorrect error code of the tool: https://github.com/digital-asset/daml/pull/889
# back to prior working directory
cd ../

echo "Launching fabric network"
cd src/test/fixture/
./restart_fabric.sh
cd ../../../

echo "Giving time for fabric network to initialise"
sleep 90

echo "Launching damlonx-example server..."
java -jar target/scala-2.12/daml-on-fabric.jar --port=6865 --role provision,time,ledger,explorer target/SemanticTests.dar & serverPid=$!
echo "Waiting for the server to start"
#crude sleep that will work cross platform
sleep 20
echo "damlonx-example server started"
echo "Launching the test tool..."
java -jar target/ledger-api-test-tool.jar -h localhost -p 6865
echo "Test tool run is complete."
echo "Killing the server..."
kill $serverPid
wait $serverPid || true # mask SIGTERM error code we should get here.
