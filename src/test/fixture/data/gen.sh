#!/bin/bash

rm -rf ./crypto-config/
cryptogen generate --config=./crypto-config.yaml
configtxgen --configPath . -outputBlock orderer.block -profile TwoOrgsOrdererGenesis_v13
