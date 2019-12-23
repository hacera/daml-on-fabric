#!/usr/bin/env bash

set -euo pipefail

echo "Detecting current DAML SDK version used in the SBT build..."
#sdkVersion=$(sbt --error 'set showSuccess := false'  printSdkVersion)
 sdkVersion=$(cat ../../../build.sbt| egrep -o "sdkVersion.*=.*\".*\"" | perl -pe 's|sdkVersion.*?=.*?"(.*?)"|\1|')
echo "Detected SDK version is $sdkVersion"

echo "Downloading DAML Integration kit Ledger API Test Tool version ${sdkVersion}..."
bintrayTestToolPath="https://bintray.com/api/v1/content/digitalassetsdk/DigitalAssetSDK/com/daml/ledger/testtool/ledger-api-test-tool/"
curl -L "${bintrayTestToolPath}${sdkVersion}/ledger-api-test-tool-${sdkVersion}.jar?bt_package=sdk-components" \
     -o ledger-api-test-tool.jar

echo "Extracting the .dar file to load in DAML-on-Fabric server..."
java -jar ledger-api-test-tool.jar --extract || true # mask incorrect error code of the tool: https://github.com/digital-asset/daml/pull/889
