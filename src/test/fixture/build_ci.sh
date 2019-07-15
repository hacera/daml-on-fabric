#!/bin/bash

# This script builds Docker images required for CI (so that whole CI environment runs inside Docker-Compose)

set -e

rm -rf ./tmp/*
cp ./ledger-api-test-tool.jar tmp
cp ../../../target/scala-2.12/daml-on-fabric.jar tmp
cp ./config-ci.json tmp/config.json
cp -r ./chaincode tmp
cp -r ./data tmp
cp ./Dockerfile tmp
cd tmp
docker build . -t hacera/daml-on-fabric:v1
