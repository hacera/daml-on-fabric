// (c) 2019 HACERA

package main

import (
	"encoding/base64"
	"encoding/binary"
	"fmt"
	"strconv"

	"github.com/hyperledger/fabric-chaincode-go/shim"
	pb "github.com/hyperledger/fabric-protos-go/peer"
	"github.com/op/go-logging"
)

var logger = logging.MustGetLogger("daml_on_fabric")

const (
	_prefixState     = "DState:"
	_commitLogIndex  = "DCommitLogIndex"
	_prefixCommitLog = "DCommitLog:"
	_commitLogStart  = _prefixCommitLog + "0000000000000000"
	_commitLogEnd    = _prefixCommitLog + "ffffffffffffffff"
	_prefixPackages  = "DPackages:"
	_packagesList    = "DPackagesList"
	_packagesStart   = _prefixPackages + "0000000000000000000000000000000000000000000000000000000000000000"
	_packagesEnd     = _prefixPackages + "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
	_recordTime      = "DRecordTime"
	_ledgerID        = "DLedgerID"
)

// DamlOnFabric implements storage of DAML data on Fabric
type DamlOnFabric struct {
}

// Init initializes the chaincode state
func (t *DamlOnFabric) Init(stub shim.ChaincodeStubInterface) pb.Response {
	// there is no special init here

	return shim.Success(nil)

}

// Invoke handles subfunctions
func (t *DamlOnFabric) Invoke(stub shim.ChaincodeStubInterface) pb.Response {
	function, args := stub.GetFunctionAndParameters()
	rawArgs := stub.GetArgs()[1:]

	if function == "RawWrite" {

		return t.rawWrite(stub, args, rawArgs)

	} else if function == "RawRead" {

		return t.rawRead(stub, args, rawArgs)

	} else if function == "WriteCommitLog" {

		return t.writeCommitLog(stub, args, rawArgs)

	} else if function == "ReadCommit" {

		return t.readCommit(stub, args, rawArgs)

	} else if function == "ReadCommitHeight" {

		return t.readCommitHeight(stub, args, rawArgs)

	} else if function == "PackageRead" {

		return t.packageRead(stub, args, rawArgs)

	} else if function == "PackageWrite" {

		return t.packageWrite(stub, args, rawArgs)

	} else if function == "PackageListRead" {

		return t.packageListRead(stub, args, rawArgs)

	} else if function == "RecordTimeWrite" {

		return t.recordTimeWrite(stub, args, rawArgs)

	} else if function == "RecordTimeRead" {

		return t.recordTimeRead(stub, args, rawArgs)

	} else if function == "LedgerIDWrite" {

		return t.ledgerIDWrite(stub, args, rawArgs)

	} else if function == "LedgerIDRead" {

		return t.ledgerIDRead(stub, args, rawArgs)

	}

	// not implemented yet

	logger.Errorf("Unknown action, check the first argument, must be one of 'RawWrite', 'RawRead'. But got: %v", function)
	return shim.Error(fmt.Sprintf("Unknown action, check the first argument, must be one of 'RawWrite', 'RawRead'. But got: %v", function))
}

// rawWrite: writes a value to K/V world state
func (t *DamlOnFabric) rawWrite(stub shim.ChaincodeStubInterface, args []string, rawArgs [][]byte) pb.Response {

	if len(rawArgs) != 2 {
		return shim.Error(fmt.Sprintf("Expected 2 arguments (Key, Value), got %d", len(rawArgs)))
	}

	mappedKey := fmt.Sprintf("%s%s", _prefixState, base64.StdEncoding.EncodeToString(rawArgs[0]))

	// in rawArgs, [2] should be the byte object to store
	err := stub.PutState(mappedKey, rawArgs[1])
	if err != nil {
		return shim.Error(fmt.Sprintf("Error writing Fabric state: %s", err.Error()))
	}

	return shim.Success([]byte{})

}

// rawRead: reads a value from K/V world state directly
func (t *DamlOnFabric) rawRead(stub shim.ChaincodeStubInterface, args []string, rawArgs [][]byte) pb.Response {

	if len(rawArgs) != 1 {
		return shim.Error(fmt.Sprintf("Expected 1 argument (Key), got %d", len(rawArgs)))
	}

	mappedKey := fmt.Sprintf("%s%s", _prefixState, base64.StdEncoding.EncodeToString(rawArgs[0]))

	value, err := stub.GetState(mappedKey)
	if err != nil {
		return shim.Error(fmt.Sprintf("Error reading Fabric state: %s", err.Error()))
	}

	return shim.Success(value)

}

// writeCommitLog: writes a Commit incrementally
func (t *DamlOnFabric) writeCommitLog(stub shim.ChaincodeStubInterface, args []string, rawArgs [][]byte) pb.Response {

	if len(rawArgs) != 1 {
		return shim.Error(fmt.Sprintf("Expected 1 argument (Commit), got %d", len(rawArgs)))
	}

	// get current commit entry
	var commitLogIndex int
	commitLogIndexBytes, err := stub.GetState(_commitLogIndex)
	if err != nil {
		return shim.Error(fmt.Sprintf("Error reading Fabric state: %s", err.Error()))
	}

	if commitLogIndexBytes != nil && len(commitLogIndexBytes) == 4 {
		commitLogIndex = int(binary.LittleEndian.Uint32(commitLogIndexBytes))
	} else {
		commitLogIndexBytes = nil
		commitLogIndex = 0
	}

	//
	commitKey := fmt.Sprintf("%s%08x", _prefixCommitLog, commitLogIndex)

	err = stub.PutState(commitKey, rawArgs[0])
	if err != nil {
		return shim.Error(fmt.Sprintf("Error writing Fabric state: %s", err.Error()))
	}

	commitLogIndex++
	if commitLogIndexBytes == nil {
		commitLogIndexBytes = make([]byte, 4)
	}
	binary.LittleEndian.PutUint32(commitLogIndexBytes, uint32(commitLogIndex))
	logger.Infof("writing commitLogIndex as %d to %s", commitLogIndex, _commitLogIndex)

	err = stub.PutState(_commitLogIndex, commitLogIndexBytes)
	if err != nil {
		return shim.Error(fmt.Sprintf("Error writing Fabric state: %s", err.Error()))
	}

	return shim.Success(commitLogIndexBytes)

}

// readCommit: reads a Commit by index
func (t *DamlOnFabric) readCommit(stub shim.ChaincodeStubInterface, args []string, rawArgs [][]byte) pb.Response {

	if len(args) != 1 {
		return shim.Error(fmt.Sprintf("Expected 1 argument (Index), got %d", len(args)))
	}

	var i int
	i64, err := strconv.ParseInt(args[0], 10, 32)
	i = int(i64)
	if err != nil {
		return shim.Error(fmt.Sprintf("Expected an integer for Index, got '%s'", args[0]))
	}

	// get current commit entry
	var commitLogIndex int
	commitLogIndexBytes, err := stub.GetState(_commitLogIndex)
	if err != nil {
		return shim.Error(fmt.Sprintf("Error reading Fabric state: %s", err.Error()))
	}

	if commitLogIndexBytes != nil && len(commitLogIndexBytes) == 4 {
		commitLogIndex = int(binary.LittleEndian.Uint32(commitLogIndexBytes))
		logger.Infof("reading commitLogIndex as %d from %s", commitLogIndex, _commitLogIndex)
	} else {
		logger.Infof("failed reading commitLogIndex from %s", _commitLogIndex)
		return shim.Error(fmt.Sprintf("Commit log is not initialized yet, requested index %d", i))
	}

	if i < 0 || i >= commitLogIndex {
		return shim.Error(fmt.Sprintf("Expected Index to be between %d and %d, got %d", 0, commitLogIndex-1, i))
	}

	commitKey := fmt.Sprintf("%s%08x", _prefixCommitLog, i)

	commit, err := stub.GetState(commitKey)
	if err != nil {
		return shim.Error(fmt.Sprintf("Error reading Fabric state: %s", err.Error()))
	}

	return shim.Success(commit)

}

// readCommit: reads a Commit by index
func (t *DamlOnFabric) readCommitHeight(stub shim.ChaincodeStubInterface, args []string, rawArgs [][]byte) pb.Response {

	if len(args) != 0 {
		return shim.Error(fmt.Sprintf("Expected no arguments, got %d", len(args)))
	}

	// get current commit entry
	commitLogIndexBytes, err := stub.GetState(_commitLogIndex)
	if err != nil {
		return shim.Error(fmt.Sprintf("Error reading Fabric state: %s", err.Error()))
	}

	if commitLogIndexBytes != nil && len(commitLogIndexBytes) == 4 {
		logger.Infof("reading commitLogIndex as %d from %s", _commitLogIndex)
		return shim.Success(commitLogIndexBytes)
	} else {
		logger.Infof("failed reading commitLogIndex from %s, height is 0", _commitLogIndex)
		return shim.Success([]byte{0, 0, 0, 0}) // height of 0
	}

}

// packageWrite: writes an installed package by hash
func (t *DamlOnFabric) packageWrite(stub shim.ChaincodeStubInterface, args []string, rawArgs [][]byte) pb.Response {

	if len(rawArgs) != 2 {
		return shim.Error(fmt.Sprintf("Expected 2 arguments (PackageID, Archive), got %d", len(rawArgs)))
	}

	mappedKey := fmt.Sprintf("%s%s", _prefixPackages, args[0])

	// in rawArgs, [2] should be the byte object to store
	err := stub.PutState(mappedKey, rawArgs[1])
	if err != nil {
		return shim.Error(fmt.Sprintf("Error writing Fabric state: %s", err.Error()))
	}

	return shim.Success([]byte{})

}

// packageRead: reads an installed package by hash
func (t *DamlOnFabric) packageRead(stub shim.ChaincodeStubInterface, args []string, rawArgs [][]byte) pb.Response {

	if len(rawArgs) != 1 {
		return shim.Error(fmt.Sprintf("Expected 1 argument (PackageID), got %d", len(rawArgs)))
	}

	mappedKey := fmt.Sprintf("%s%s", _prefixPackages, args[0])

	value, err := stub.GetState(mappedKey)
	if err != nil {
		return shim.Error(fmt.Sprintf("Error reading Fabric state: %s", err.Error()))
	}

	return shim.Success(value)

}

// packageListRead:
func (t *DamlOnFabric) packageListRead(stub shim.ChaincodeStubInterface, args []string, rawArgs [][]byte) pb.Response {

	if len(rawArgs) != 0 {
		return shim.Error(fmt.Sprintf("Expected no arguments, got %d", len(rawArgs)))
	}

	//
	keysIter, err := stub.GetStateByRange(_packagesStart, _packagesEnd)
	if err != nil {
		return shim.Error(err.Error())
	}

	output := make([]byte, 4)
	count := 0

	for keysIter.HasNext() {
		key, iterErr := keysIter.Next()
		if iterErr != nil {
			return shim.Error(iterErr.Error())
		}

		s := []byte(key.GetKey()[len(_prefixPackages):])
		singleKey := make([]byte, 4+len(s))
		copy(singleKey[4:len(singleKey)], s)
		binary.LittleEndian.PutUint32(singleKey[0:4], uint32(len(s)))
		output = append(output, singleKey...)
		count++
	}

	binary.LittleEndian.PutUint32(output[0:4], uint32(count))
	return shim.Success(output)

}

// recordTimeWrite:
func (t *DamlOnFabric) recordTimeWrite(stub shim.ChaincodeStubInterface, args []string, rawArgs [][]byte) pb.Response {

	if len(rawArgs) != 1 {
		return shim.Error(fmt.Sprintf("Expected 1 argument (RecordTime), got %d", len(args)))
	}

	err := stub.PutState(_recordTime, rawArgs[0])
	if err != nil {
		return shim.Error(fmt.Sprintf("Error writing Fabric state: %s", err.Error()))
	}

	return shim.Success(rawArgs[0])

}

// recordTimeRead:
func (t *DamlOnFabric) recordTimeRead(stub shim.ChaincodeStubInterface, args []string, rawArgs [][]byte) pb.Response {

	if len(rawArgs) != 0 {
		return shim.Error(fmt.Sprintf("Expected no arguments, got %d", len(rawArgs)))
	}

	rt, err := stub.GetState(_recordTime)
	if err != nil {
		return shim.Error(fmt.Sprintf("Error reading Fabric state: %s", err.Error()))
	}

	return shim.Success(rt)

}

// ledgerIDWrite:
func (t *DamlOnFabric) ledgerIDWrite(stub shim.ChaincodeStubInterface, args []string, rawArgs [][]byte) pb.Response {

	if len(rawArgs) != 1 {
		return shim.Error(fmt.Sprintf("Expected 1 argument (LedgerID), got %d", len(args)))
	}

	err := stub.PutState(_ledgerID, rawArgs[0])
	if err != nil {
		return shim.Error(fmt.Sprintf("Error writing Fabric state: %s", err.Error()))
	}

	return shim.Success(rawArgs[0])

}

// ledgerIDRead:
func (t *DamlOnFabric) ledgerIDRead(stub shim.ChaincodeStubInterface, args []string, rawArgs [][]byte) pb.Response {

	if len(rawArgs) != 0 {
		return shim.Error(fmt.Sprintf("Expected no arguments, got %d", len(rawArgs)))
	}

	rt, err := stub.GetState(_ledgerID)
	if err != nil {
		return shim.Error(fmt.Sprintf("Error reading Fabric state: %s", err.Error()))
	}

	return shim.Success(rt)

}

func main() {
	err := shim.Start(new(DamlOnFabric))
	if err != nil {
		logger.Errorf("Error starting DamlOnFabric chaincode: %s", err)
	}
}
