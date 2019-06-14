#!/usr/bin/env bash
#
# Copyright IBM Corp. All Rights Reserved.
#
# SPDX-License-Identifier: Apache-2.0
#
# simple batch script making it easier to cleanup and start a relatively fresh fabric env.

if [ ! -e "docker-compose.yaml" ];then
  echo "docker-compose.yaml not found."
  exit 8
fi

if [ "$DOCKER_COMPOSE_FILE" == "" ]; then
  DOCKER_COMPOSE_FILE="docker-compose.yaml"
fi

ORG_HYPERLEDGER_FABRIC_SDKTEST_VERSION="2.0.0"
IMAGE_TAG_FABRIC_CA=":2.0.0-alpha"
IMAGE_TAG_FABRIC=":2.0.0-alpha"

function clean(){

  rm -rf /var/hyperledger/*

  if [ -e "/tmp/HFCSampletest.properties" ];then
    rm -f "/tmp/HFCSampletest.properties"
  fi

  lines=`docker ps -a | grep 'dev-peer' | wc -l`

  if [ "$lines" -gt 0 ]; then
    docker ps -a | grep 'dev-peer' | awk '{print $1}' | xargs docker rm -f
  fi

  lines=`docker images | grep 'dev-peer' | grep 'dev-peer' | wc -l`
  if [ "$lines" -gt 0 ]; then
    docker images | grep 'dev-peer' | awk '{print $1}' | xargs docker rmi -f
  fi

}

function updetached()

  if [ "$ORG_HYPERLEDGER_FABRIC_SDKTEST_VERSION" == "1.0.0" ]; then
    docker-compose -p daml-on-fabric -f $DOCKER_COMPOSE_FILE up -d --force-recreate ca0 ca1 peer1.org1.example.com peer1.org2.example.com
  else
    docker-compose -p daml-on-fabric -f $DOCKER_COMPOSE_FILE up -d --force-recreate 
fi

function up(){

  if [ "$ORG_HYPERLEDGER_FABRIC_SDKTEST_VERSION" == "1.0.0" ]; then
    docker-compose -p daml-on-fabric -f $DOCKER_COMPOSE_FILE up --force-recreate ca0 ca1 peer1.org1.example.com peer1.org2.example.com
  else
    docker-compose -p daml-on-fabric -f $DOCKER_COMPOSE_FILE up --force-recreate 
fi

}

function down(){
  docker-compose -p daml-on-fabric -f $DOCKER_COMPOSE_FILE down;
}

function stop (){
  docker-compose -p daml-on-fabric -f $DOCKER_COMPOSE_FILE stop;
}

function start (){
  docker-compose -p daml-on-fabric -f $DOCKER_COMPOSE_FILE start;
}


for opt in "$@"
do

    case "$opt" in
        up)
            up
            ;;
        updetached)
            updetached
            ;;
        down)
            down
            ;;
        stop)
            stop
            ;;
        start)
            start
            ;;
        clean)
            clean
            ;;
        restart)
            down
            clean
            up
            ;;

        *)
            echo $"Usage: $0 {up|down|start|stop|clean|restart}"
            exit 1

esac
done

