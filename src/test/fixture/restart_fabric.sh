#!/bin/bash

./fabric.sh down
rm ./client/hfc-key-store/*
./fabric.sh up
