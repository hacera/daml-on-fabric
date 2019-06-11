#!/usr/bin/env bash

set -euo pipefail

echo "Detecting current DAML SDK version used in the SBT build..."
sdkVersion=$(sbt --error 'set showSuccess := false'  printSdkVersion)
# sdkVersion=$(cat build.sbt| egrep -o "sdkVersion.*=.*\".*\"" | perl -pe 's|sdkVersion.*?=.*?"(.*?)"|\1|')
echo "Detected SDK version is $sdkVersion"

echo "Downloading DAML Integration kit Ledger API Test Tool version ${sdkVersion}..."
curl -L "https://bintray.com/api/v1/content/digitalassetsdk/DigitalAssetSDK/com/daml/ledger/testtool/ledger-api-test-tool_2.12/${sdkVersion}/ledger-api-test-tool_2.12-${sdkVersion}.jar?bt_package=sdk-components" \
     -o ledger-api-test-tool.jar

java -jar ledger-api-test-tool.jar --extract || true # mask incorrect error code of the tool: https://github.com/digital-asset/daml/pull/889
echo "Launching damlonx-example server..."
java -jar target/scala-2.12/damlonx-example.jar --port=6865 SemanticTests.dar & serverPid=$!
printf "Waiting for the server to start"
while ! timeout 1 bash -c "echo > /dev/tcp/localhost/6865"; do
    printf "."
		sleep 1
done
echo
echo "Launching the test tool..."
java -jar ledger-api-test-tool.jar -h localhost -p 6865
echo "Test tool run is complete."
echo "Killing the server..."
kill $serverPid
wait $serverPid || true # mask SIGTERM error code we should get here.
