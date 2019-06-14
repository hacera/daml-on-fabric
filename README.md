# DAML on Fabric

This is an implementation of DAML ledger that stores data (transactions and state) using Hyperledger Fabric 1.4 LTS.

## Prerequisites

- Java 1.8.0
- SBT 1.2.8
- Scala 2.12.7

## Commands to make it work in a minimal way

1. `cd src/test/fixture && export DOCKER_COMPOSE_FILE=docker-compose.yaml && ./fabric.sh down && ./fabric.sh up` — this will start a Fabric network required by the service.
2. `sbt "run --port 6865 --role ledger,explorer,provision,time <archive>" ` — this will run the actual service.

**A bit more on Role**

The "role" parameter controls how exactly the service will function.

Original in-memory ledger is doing everything in a single service, role switch allows us to run either parts of it.

- `ledger` — executes the Ledger API which reads and writes the chain.
- `time` — executes the Time Service which writes current time to the chain.
- `explorer` — executes the Fabric Block Explorer which reads block info from Fabric and provides a REST API.
- `provision` — after connecting to Fabric, will automatically try to install latest chaincode and/or create a channel.

Note that only `ledger` and `explorer` services can have multiple instances (explorer — only on multiple machines). Running multiple time or provision services may cause a conflict.

## Testing

Use SemanticTests.dar as the archive argument to the command #3, then just run API tests as usual without parameters (it should connect to port 6865 by default) 

## Running the Navigator

This requires DAML SDK to be installed in the system. See https://daml.com/ on how to install it.

Old SDK:

```
da run navigator -- server <ledger host> <ledger port> --port <HTTP port>
```

New SDK:

```
daml navigator server <ledger host> <ledger port> --port <HTTP port>
```
