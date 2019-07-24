// (c) 2019 The Unbounded Network LTD
package com.hacera;

// this is used with Jodd JSON to read config file from disk

import java.util.List;

// to-do: maybe use YAML? it supports comments...
public class FabricContextConfig {
    
    // peer or orderer, or CA
    public static class NodeConfig {
        public String organization;
        public String name;
        public String url;
        public String adminMsp;
        public String mspId;
        
        public NodeConfig() {}
    }
    
    // chaincode info
    public static class ChaincodeConfig {
        public String gopath;
        public String entryPath;
        public String meta;
        
        public ChaincodeConfig() {}
    }
    
    // this is specifically for the demo client
    public static class RestConfig {
        public int port;
        
        public RestConfig() {}
    }
    
    public String msp;
    public NodeConfig peer;
    public List<NodeConfig> peers;
    public NodeConfig orderer;
    public NodeConfig ca;
    public ChaincodeConfig chaincode;
    public String channelId;
    public List<String> channelMsps;
    public String peerAdminMsp;
    public String ordererAdminMsp;
    public String endorsementPolicy;
    public RestConfig rest;
    public String ledgerId;
    
}
