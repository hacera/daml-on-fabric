[//]: # (Copyright The Unbounded Network LTD)

# DAML on Fabric

This is an implementation of DAML ledger that stores data (transactions and state) using Hyperledger Fabric 1.4 LTS.

# Quick Start Guide

## Prerequisites

These are the minimal requirements that this flow was tested with. It's good to use greater versions or sometimes even lower, but not advised.

- **docker-compose** version 1.24.0
- **docker CE** version 18.09.6
- **Java / JDK** version 1.8.0
- **Scala** version 2.12.7
- **SBT** version 1.2.8
- **DAML SDK** version 0.13.38

Docker and Docker-Compose are required to run a Hyperledger Fabric network, everything else is a typical dependency list for building and running a DAML ledger.

Note: running Docker as root is not recommended, this may cause issues.

## Cloning DAML-on-Fabric

For the sake of cleanness, we are using the directory `~/daml-on-fabric` as the root for the repository.

```
$ cd
$ git clone https://github.com/hacera/daml-on-fabric.git
```

## Running a local Hyperledger Fabric network

This is achieved by running a Docker-Compose network which is a typical way to set up Fabric for development environments. Our particular example sets up a network of 5 peers, of which two will be used for endorsement.

```
$ cd ~/daml-on-fabric/src/test/fixture/
$ ./restart_fabric.sh
```

The script used is a shortcut for `./fabric.sh down && ./fabric.sh up` that will erase all current data and start a fresh network.

## DAML-on-Fabric command line and Services

The basic command to run the ledger right from the repository is like this:

```
$ sbt "run --role <roles> [--port NNNN] [DAMLArchive.dar DAMLArchive2.dar ...]"
```

**Important: the ledger will connect to a Fabric network specified in *config.json* file.**

Generally, if you run the ledger not against a local network, you need to provide additional argument to SBT, like this: 

```
$ sbt "run ..." -J-DfabricConfigFile=<configuration file>
```

By default, it will use "config.json", which you can use for reference.

#### Services, or "Roles"

You may have noticed that the required argument for DAML-on-Fabric is "role".

There are several roles that define which parts of the service are going to be executed:

- `provision`: will connect to Fabric and prepare it to be used as storage for a DAML ledger.
- `ledger`: will run a DAML Ledger API endpoint at a port specified with `--port` argument.
- `time`: will run a DAML time service, which writes heartbeats to the ledger. There should be exactly one time service per ledger.
- `explorer`: will run a block explorer service that exposes a REST API to view the content of blocks and transactions inside the Fabric network for debugging or demonstration purposes. The explorer will run at a port specified in *config.json* file. It provides REST API that responds at endpoints `/blocks[?id=...]` and `/transactions[?id=...]`

One ledger may perform multiple roles at the same time, in which case roles are separated with comma. Example of this will be given later (we are using a single node just for the example).

## Running Java Quick Start against DAML-on-Fabric

### Step 1. Start Hyperledger Fabric

```
$ cd ~/daml-on-fabric/src/test/fixture/
$ ./restart_fabric.sh
```

### Step 2. Extract and build Quick Start project 

```
$ cd
$ rm -rf quickstart
$ daml new quickstart quickstart-java
Created a new project in "quickstart" based on the template "quickstart-java".
$ cd ~/quickstart/
$ daml build
Compiling daml/Main.daml to a DAR.
Created .daml/dist/quickstart.dar.
```

### Step 3. Run the Ledger with Quick Start DAR archive

```
$ cd ~/daml-on-fabric/
$ sbt "run --port 6865 --role provision,time,ledger,explorer ../quickstart/.daml/dist/quickstart-0.0.1.dar"
```

### Step 4. Run DAML Navigator

```
$ cd ~/quickstart/
daml navigator server localhost 6865 --port 4000
```

### Step 5. Conclusion

Now you can explore your freshly setup DAML ledger.

You should have the following services running:

- DAML Navigator at http://localhost:4000/
- Hyperledger Fabric block explorer at http://localhost:8080/
- DAML Ledger API endpoint at *localhost:6865*

More information on Quick Start example and DAML in general can be found here:

https://docs.daml.com/getting-started/quickstart.html

### Step 6.  Running a Multi-node Setup
## Start Fabric Network
- `cd src/test/fixture && ./restart_fabric.sh`

## Output DAR from test tool
- `bazel run -- //ledger/ledger-api-test-tool:ledger-api-test-tool -x`
- `cp <extracted_location>/*.dar ~/daml-on-fabric`

## First Participant Node
- `sbt "run --role ledger,time,provision --port 11111" -J-DfabricConfigFile=config.json`

## Second Participant Node
- `sbt "run --role ledger --port 12222" -J-DfabricConfigFile=config.json`

## Third Participant Node
- `sbt "run --role ledger --port 13333 SemanticTests.dar Test.dar" -J-DfabricConfigFile=config.json`

## Run Ledger Test Tool against all nodes
- `bazel run -- //ledger/ledger-api-test-tool:ledger-api-test-tool --target-port=11111 --mapping:Alice=localhost:11111 --mapping:Bank=localhost:12222 --mapping:Peggy=localhost:13333 --include=SemanticTests`
