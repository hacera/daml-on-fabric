# Dockerfile for running DAML-on-Fabric as a Docker container
# used with build_ci.sh

FROM openjdk:8-jre

WORKDIR /daml-on-fabric
COPY daml-on-fabric.jar .
COPY config.json .
COPY ledger-api-test-tool.jar .

WORKDIR /daml-on-fabric/data
COPY data .

WORKDIR /daml-on-fabric/chaincode
COPY chaincode .

WORKDIR /daml-on-fabric

RUN java -jar ledger-api-test-tool.jar -x localhost:111111

CMD /bin/bash -c "sleep $LEDGER_DELAY; java -jar daml-on-fabric.jar --port $LEDGER_PORT --role $LEDGER_ROLES $LEDGER_ARGS"
