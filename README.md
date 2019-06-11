# DAML on Fabric

This is an implementation of DAML ledger that stores data (transactions and state) using Hyperledger Fabric 2.0.

## Prerequisites

- Java 1.8.0
- Maven 3.6.1
- SBT 1.2.8
- Scala 2.12.7

## Commands to make it work in a minimal way

1. `cd src/test/fixture && export DOCKER_COMPOSE_FILE=docker-compose.yaml && ./fabric.sh down && ./fabric.sh up` — this will start a Fabric network required by the service.
2. `mvn compile assembly:single` — this will build Java part of the project.
3. `sbt "run --port 6865 <archive>" ` — this will run the actual service.

## Testing

Use SemanticTests.dar as the archive argument to the command #3, then just run API tests as usual without parameters (it should connect to port 6865 by default) 