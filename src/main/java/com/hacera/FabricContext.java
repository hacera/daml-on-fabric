// (c) 2019 The Unbounded Network LTD
package com.hacera;

import com.google.common.base.Strings;
import com.google.protobuf.ByteString;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import jodd.json.JsonParser;
import org.hyperledger.fabric.protos.common.Common.ChannelHeader;
import org.hyperledger.fabric.protos.common.Common.Envelope;
import org.hyperledger.fabric.protos.common.Common.Header;
import org.hyperledger.fabric.protos.common.Common.HeaderType;
import org.hyperledger.fabric.protos.common.Common.Payload;
import org.hyperledger.fabric.protos.common.Configtx.ConfigGroup;
import org.hyperledger.fabric.protos.common.Configtx.ConfigPolicy;
import org.hyperledger.fabric.protos.common.Configtx.ConfigUpdate;
import org.hyperledger.fabric.protos.common.Configtx.ConfigUpdateEnvelope;
import org.hyperledger.fabric.protos.common.Configtx.ConfigValue;
import org.hyperledger.fabric.protos.common.Configuration.Capabilities;
import org.hyperledger.fabric.protos.common.Configuration.Capability;
import org.hyperledger.fabric.protos.common.Policies;
import org.hyperledger.fabric.protos.common.Policies.ImplicitMetaPolicy;
import org.hyperledger.fabric.protos.common.Policies.Policy;
import org.hyperledger.fabric.protos.common.Policies.SignaturePolicy;
import org.hyperledger.fabric.protos.common.Policies.SignaturePolicy.NOutOf;
import org.hyperledger.fabric.protos.orderer.Configuration;
import org.hyperledger.fabric.protos.orderer.Configuration.BatchSize;
import org.hyperledger.fabric.protos.orderer.Configuration.BatchTimeout;
import org.hyperledger.fabric.protos.peer.FabricProposal;
import org.hyperledger.fabric.protos.peer.FabricProposalResponse;
import org.hyperledger.fabric.protos.peer.Query;
import org.hyperledger.fabric.sdk.ChaincodeEndorsementPolicy;
import org.hyperledger.fabric.sdk.ChaincodeID;
import org.hyperledger.fabric.sdk.ChaincodeResponse.Status;
import org.hyperledger.fabric.sdk.Channel;
import org.hyperledger.fabric.sdk.Channel.TransactionOptions;
import static org.hyperledger.fabric.sdk.Channel.TransactionOptions.createTransactionOptions;
import org.hyperledger.fabric.sdk.ChannelConfiguration;
import org.hyperledger.fabric.sdk.Enrollment;
import org.hyperledger.fabric.sdk.HFClient;
import org.hyperledger.fabric.sdk.InstallProposalRequest;
import org.hyperledger.fabric.sdk.InstantiateProposalRequest;
import org.hyperledger.fabric.sdk.Orderer;
import org.hyperledger.fabric.sdk.Peer;
import org.hyperledger.fabric.sdk.ProposalResponse;
import org.hyperledger.fabric.sdk.QueryByChaincodeRequest;
import org.hyperledger.fabric.sdk.TransactionProposalRequest;
import org.hyperledger.fabric.sdk.TransactionRequest;
import org.hyperledger.fabric.sdk.User;
import org.hyperledger.fabric.sdk.exception.InvalidArgumentException;
import org.hyperledger.fabric.sdk.identity.X509Enrollment;
import org.hyperledger.fabric.sdk.security.CryptoSuite;
import org.hyperledger.fabric.sdk.transaction.ProposalBuilder;
import org.hyperledger.fabric.sdk.transaction.TransactionContext;
import org.hyperledger.fabric_ca.sdk.HFCAClient;
import org.hyperledger.fabric_ca.sdk.RegistrationRequest;

/**
 * This class implements reading the configuration file (for Fabric connectivity)
 *   it also generates FabricClient and FabricCAClient instances based on these settings.
 *   for now, settings are hardcoded.
 * It also provides queryChaincode and invokeChaincode methods.
 * 
 * The known bad side to this code is that it always works on a single endorsing peer.
 * Otherwise, it provides most necessary low-level boilerplate to start working with a Fabric network.
 * 
 * This class also ensures that:
 *   - we have a valid user context (peer admin by default)
 *   - we have a channel that's created and initialized, with name defined by code (not ready configtx)
 */
public final class FabricContext {

    private HFClient fabClient;
    private HFCAClient fabCAClient;
    private Channel fabChannel;
    
    //
    private String ccMetaId;
    private String ccMetaVersion;
    
    private final FabricContextConfig config;
    //
    
    // to-do: remove this
    public FabricContextConfig getConfig() {
        return config;
    }

    private void debugOut(String fmt, Object... params) {
        System.out.append(String.format(fmt+"%n", params));
        System.out.flush();
    }
    
    private void debugOut(String fmt, Object param) {
        debugOut(fmt, new Object[]{param});
    }
    
    private Properties createProperties(double timeout, String certFile, String domainOverride) {
        
        Properties props = new Properties();
        // read cert file
        File cert = new File(certFile);
        if (!cert.exists()) {
            throw new FabricContextException(String.format("TLS Certificate for \"%s\" not found or not readable (at %s)", domainOverride, certFile));
        }
        
        // set cert property
        props.setProperty("pemFile", cert.getAbsolutePath());
        props.setProperty("hostnameOverride", domainOverride);
        // not sure why is this needed:
        props.setProperty("sslProvider", "openSSL");
        props.setProperty("negotiationType", "TLS");
        // set timeout
        props.setProperty("ordererWaitTimeMilliSecs", String.format("%d", (int)(timeout * 1000)));
        props.put("grpc.NettyChannelBuilderOption.maxInboundMessageSize", 1024*1024*100); // really large inbound message size

        return props;
        
    }

    private Orderer createOrderer(String ordererURL, String ordererName, String orgName) {
        
        String certFile = String.format("%s/ordererOrganizations/%s/orderers/%s.%s/tls/ca.crt", config.msp, orgName, ordererName, orgName);
        
        try {
            
            Orderer orderer = fabClient.newOrderer(ordererName, ordererURL, createProperties(30, certFile, String.format("%s.%s", ordererName, orgName)));
            return orderer;
            
        } catch (Throwable t) {
            if (RuntimeException.class.isAssignableFrom(t.getClass())) {
                throw (RuntimeException)t;
            } else {
                throw new FabricContextException(t);
            }
        }
        
    }
    
    private Peer createPeer(String peerURL, String peerName, String orgName) {
        
        String certFile = String.format("%s/peerOrganizations/%s/peers/%s.%s/tls/ca.crt", config.msp, orgName, peerName, orgName);
        
        try {
            
            Peer peer = fabClient.newPeer(peerName, peerURL, createProperties(30, certFile, String.format("%s.%s", peerName, orgName)));
            return peer;
            
        } catch (Throwable t) {
            if (RuntimeException.class.isAssignableFrom(t.getClass())) {
                throw (RuntimeException)t;
            } else {
                throw new RuntimeException(t);
            }
        }
        
    }
    
    public FabricUser getPeerAdmin(Peer fabPeer) {
        
        FabricContextConfig.NodeConfig peer = null;
        for (FabricContextConfig.NodeConfig foundPeer : config.peers) {
            String peerName = String.format("%s.%s", foundPeer.name, foundPeer.organization);
            String fabPeerName = fabPeer.getProperties().getProperty("hostnameOverride");
            if (peerName.compareToIgnoreCase(fabPeerName) == 0) {
                peer = foundPeer;
                break;
            }
        }
        
        if (peer == null) {
            throw new FabricContextException(String.format("Config not found for peer [%s]!", fabPeer.getName()));
        }
        
        return getPeerAdmin(peer);
        
    }
    
    public FabricUser getPeerAdmin(FabricContextConfig.NodeConfig peer) {
        
        String finalName = String.format("Admin@%s", peer.organization);
        String skPath = String.format("%s/keystore", peer.adminMsp);
        String certPath = String.format("%s/signcerts", peer.adminMsp);
        return getLocalUser("Peer Admin", finalName, skPath, certPath, peer.organization, peer.mspId);
        
    }
    
    public FabricUser getOrdererAdmin() {
        
        String finalName = String.format("Admin@%s", config.orderer.organization);
        String skPath = String.format("%s/keystore", config.orderer.adminMsp);
        String certPath = String.format("%s/signcerts", config.orderer.adminMsp);
        return getLocalUser("Orderer Admin", finalName, skPath, certPath, config.orderer.organization, config.orderer.mspId);
        
    }
    
    public String getChaincodeId() {
        return ccMetaId;
    }
    
    public String getChaincodeVersion() {
        return ccMetaVersion;
    }
    
    private FabricUser getLocalUser(String type, String finalName, String skPath, String certPath, String orgName, String mspid) {
        
        File skFile = null;
        File certFile = null;
        
        try {
            
            // find private key. in theory this can be found somehow... mathematically, but easier to just find it like this
            for (final File ent : new File(skPath).listFiles()) {
                if (!ent.isFile()) continue;
                if (ent.getName().endsWith("_sk")) {
                    skFile = ent;
                    break;
                }
            }
            
            certFile = new File(String.format("%s/%s-cert.pem", certPath, finalName));
            
        } catch (Throwable t) {
            if (RuntimeException.class.isAssignableFrom(t.getClass())) {
                throw (RuntimeException)t;
            } else {
                throw new RuntimeException(t);
            }
        }
        
        if (skFile == null || !skFile.exists() || !certFile.exists()) {
            if (skFile == null || !skFile.exists()) {
                throw new FabricContextException(String.format("%s private key does not exist at %s", type, (skFile==null)?"<null>":skFile.getAbsolutePath()));
            } else {
                throw new FabricContextException(String.format("%s signed certificate does not exist at %s", type, certFile.getAbsolutePath()));
            }
        }
        
        // do some debug logging
        debugOut("%s private key: %s", type, skFile.getAbsolutePath());
        debugOut("%s sign cert: %s", type, certFile.getAbsolutePath());
        
        // read in the cert
        String certPem = "";
        String skPem = "";
        try {
            
            skPem = new String(Files.readAllBytes(Paths.get(skFile.getAbsolutePath())), StandardCharsets.UTF_8);
            certPem = new String(Files.readAllBytes(Paths.get(certFile.getAbsolutePath())), StandardCharsets.UTF_8);
            
        } catch (Throwable t) {
            if (RuntimeException.class.isAssignableFrom(t.getClass())) {
                throw (RuntimeException)t;
            } else {
                throw new RuntimeException(t);
            }
        }
        
        // read in the private key.
        // tbh, in JS this is expressed with 2-3 lines...
        skPem = skPem.replace("-----BEGIN PRIVATE KEY-----\n", "");
        skPem = skPem.replace("-----END PRIVATE KEY-----\n", "");
        skPem = skPem.replaceAll("\\n", "");
        byte[] skEncoded = Base64.getDecoder().decode(skPem);
        PrivateKey skObject = null;
        try {

            KeyFactory kf = KeyFactory.getInstance("EC");
            PKCS8EncodedKeySpec skSpec = new PKCS8EncodedKeySpec(skEncoded);
            skObject = kf.generatePrivate(skSpec);
            
        } catch (Throwable t) {
            if (RuntimeException.class.isAssignableFrom(t.getClass())) {
                throw (RuntimeException)t;
            } else {
                throw new RuntimeException(t);
            }
        }
        
        Enrollment e = new X509Enrollment(skObject, certPem);
        FabricUser u = new FabricUser("Admin", orgName, e, mspid); // to-do: store the 'Org1MSP' in the config
        return u;
        
    }
    
    // FabricContext constructor: sets up Fabric and Fabric-CA clients,
    //   loads crypto material for Peer Admin,
    //   initializes channel if needed.
    public FabricContext() {

        // load config from disk
        try {
            String configPath = System.getProperty("fabricConfigFile", "./config.json");
            config = JsonParser.create().parse(new String(Files.readAllBytes(Paths.get(configPath)), StandardCharsets.UTF_8), FabricContextConfig.class);
            // remap single peer to many peers syntax
            if (config.peers == null && config.peer != null) {
                config.peers = new LinkedList<FabricContextConfig.NodeConfig>();
                config.peers.add(config.peer);
                config.peer = null;
                if (config.peerAdminMsp != null)
                    config.peers.get(0).adminMsp = config.peerAdminMsp;
            }
            // remap orderer msp
            if (config.ordererAdminMsp != null && config.orderer.adminMsp == null)
                config.orderer.adminMsp = config.ordererAdminMsp;
        } catch (IOException e) {
            throw new FabricContextException(e);
        }
        
        try {

            fabClient = HFClient.createNewInstance();
            fabCAClient = HFCAClient.createNewInstance(config.ca.name, config.ca.url, null);
            
            // for now - local
            CryptoSuite cryptoSuite = CryptoSuite.Factory.getCryptoSuite();
            fabClient.setCryptoSuite(cryptoSuite);
            fabCAClient.setCryptoSuite(cryptoSuite);
            
            // set user context to some dummy user
            fabClient.setUserContext(getOrdererAdmin());

            //
            // try to init a channel
            try {

                // ===================================================================
                //  This block sets up an EXISTING channel (with current peer joined)
                // ===================================================================
                
                // create Orderer and Peer objects from MSP
                Orderer ord = createOrderer(config.orderer.url, config.orderer.name, config.orderer.organization);
                List<Peer> peers = new LinkedList<Peer>();
                for (FabricContextConfig.NodeConfig confPeer : config.peers) {
                    peers.add(createPeer(confPeer.url, confPeer.name, confPeer.organization));
                }
                Channel channel = fabClient.newChannel(config.channelId);
                channel.addOrderer(ord);
                
                // try to connect to Fabric
                channel.initialize();
                // try to check if channel exists, and add peer
                try {
                    
                    // check if peer is already joined to the channel.
                    // if not, join it
                    for (Peer peer : peers) {
                        
                        // set user context to peer admin
                        User peerAdmin = getPeerAdmin(peer);
                        fabClient.setUserContext(peerAdmin);
                        
                        Set<String> knownChannels = fabClient.queryChannels(peer);
                        debugOut("knownChannels for [%s] = %s", peer.getName(), knownChannels);
                        if (knownChannels.contains(config.channelId)) {
                            channel.addPeer(peer);
                        } else {
                            channel.joinPeer(peer);
                        }

                        channel.queryInstantiatedChaincodes(peer); // this WILL fail if channel does not exist... ;)
                    }
                    
                } catch (Throwable tex) {
                    channel.shutdown(true);
                    throw tex;
                }
                
                fabChannel = channel;
                
            } catch (Throwable tex) { // if we caught this exception, it most likely means the channel is not there
                
                // ==================================================================================
                //  This block sets up a NEW channel (since it doesn't exist) and joins current peer
                // ==================================================================================
                
                // create Orderer and Peer objects from MSP
                Orderer ord = createOrderer(config.orderer.url, config.orderer.name, config.orderer.organization);
                List<Peer> peers = new LinkedList<Peer>();
                for (FabricContextConfig.NodeConfig confPeer : config.peers) {
                    peers.add(createPeer(confPeer.url, confPeer.name, confPeer.organization));
                }
                
                // make channel config. again, in JS this is so much easier...
                Capabilities caps = Capabilities.newBuilder()
                        .putCapabilities("V1_3", Capability.getDefaultInstance())
                        .build();
                ByteString channelCaps = caps.toByteString();
                ConfigUpdate configUpdate = ConfigUpdate.newBuilder()
                        .setChannelId(config.channelId)
                        .setReadSet(ConfigGroup.newBuilder()
                            .setVersion(0)
                            .putGroups("Application", ConfigGroup.newBuilder()
                                .setVersion(0)
                                .putGroups("Org1MSP", ConfigGroup.newBuilder()
                                    .setVersion(0)
                                    .setModPolicy("")
                                    .build())
                                .putGroups("Org2MSP", ConfigGroup.newBuilder()
                                    .setVersion(0)
                                    .setModPolicy("")
                                    .build())
                                .putGroups("Org3MSP", ConfigGroup.newBuilder()
                                    .setVersion(0)
                                    .setModPolicy("")
                                    .build())
                                .putGroups("Org4MSP", ConfigGroup.newBuilder()
                                    .setVersion(0)
                                    .setModPolicy("")
                                    .build())
                                .putGroups("Org5MSP", ConfigGroup.newBuilder()
                                    .setVersion(0)
                                    .setModPolicy("")
                                    .build())
                                .build())
                            .putValues("Consortium", ConfigValue.newBuilder()
                                .setVersion(0)
                                .build())
                            .build())
                        .setWriteSet(ConfigGroup.newBuilder()
                            .setVersion(0)
                            .putGroups("Application", ConfigGroup.newBuilder()
                                .setVersion(1)
                                .putGroups("Org1MSP", ConfigGroup.newBuilder()
                                    .setVersion(0)
                                    .setModPolicy("")
                                    .build())
                                .putGroups("Org2MSP", ConfigGroup.newBuilder()
                                    .setVersion(0)
                                    .setModPolicy("")
                                    .build())
                                .putGroups("Org3MSP", ConfigGroup.newBuilder()
                                    .setVersion(0)
                                    .setModPolicy("")
                                    .build())
                                .putGroups("Org4MSP", ConfigGroup.newBuilder()
                                    .setVersion(0)
                                    .setModPolicy("")
                                    .build())
                                .putGroups("Org5MSP", ConfigGroup.newBuilder()
                                    .setVersion(0)
                                    .setModPolicy("")
                                    .build())
                                .putValues("Capabilities", ConfigValue.newBuilder()
                                    .setVersion(0)
                                    .setValue(channelCaps)
                                    .setModPolicy("Admins")
                                    .build())
                                .putPolicies("Admins", ConfigPolicy.newBuilder()
                                    .setPolicy(Policy.newBuilder()
                                        .setType(1) // this is an empty policy of type SignaturePolicy
                                        .setValue(ByteString.copyFrom(new byte[]{0x12, 0x0c, 0x12, 0x0a, 0x08, 0x02, 0x12, 0x02, 0x08, 0x00, 0x12, 0x02, 0x08, 0x01, 0x1a, 0x0b, 0x12, 0x09, 0x0a, 0x07, 0x4f, 0x72, 0x67, 0x31, 0x4d, 0x53, 0x50, 0x1a, 0x0b, 0x12, 0x09, 0x0a, 0x07, 0x4f, 0x72, 0x67, 0x32, 0x4d, 0x53, 0x50}))
                                        .build())
                                    .setModPolicy("Admins")
                                    .build())
                                .putPolicies("Readers", ConfigPolicy.newBuilder()
                                    .setPolicy(Policy.newBuilder()
                                        .setType(3)
                                        .setValue(ByteString.copyFrom(new byte[]{0x0a, 0x07, 0x52, 0x65, 0x61, 0x64, 0x65, 0x72, 0x73}))
                                        .build())
                                    .setModPolicy("Admins")
                                    .build())
                                .putPolicies("Writers", ConfigPolicy.newBuilder()
                                    .setPolicy(Policy.newBuilder()
                                        .setType(3)
                                        .setValue(ByteString.copyFrom(new byte[]{0x0a, 0x07, 0x57, 0x72, 0x69, 0x74, 0x65, 0x72, 0x73}))
                                        .build())
                                    .setModPolicy("Admins")
                                    .build())
                                .setModPolicy("Admins")
                                .build())
                            .putValues("Consortium", ConfigValue.newBuilder()
                                .setVersion(0)
                                .setValue(ByteString.copyFrom(new byte[]{0x0a, 0x10, 0x53, 0x61, 0x6d, 0x70, 0x6c, 0x65, 0x43, 0x6f, 0x6e, 0x73, 0x6f, 0x72, 0x74, 0x69, 0x75, 0x6d}))
                                .build())
                            .build())
                        .build();

                // construct very limited tx
                ChannelHeader channelHeader = ChannelHeader.newBuilder()
                        .setChannelId(config.channelId)
                        .setType(HeaderType.CONFIG_UPDATE_VALUE)
                        .build();
                ConfigUpdateEnvelope ccuEnv = ConfigUpdateEnvelope.newBuilder().setConfigUpdate(configUpdate.toByteString()).build();
                Payload channelPayload = Payload.newBuilder()
                    .setHeader(Header.newBuilder()
                        .setChannelHeader(channelHeader.toByteString())
                        .build())
                    .setData(ccuEnv.toByteString())
                    .build();
                Envelope cEnv = Envelope.newBuilder().setPayload(channelPayload.toByteString()).build();

                ChannelConfiguration channelConfig = new ChannelConfiguration(cEnv.toByteArray());
                //User ordererAdmin = getOrdererAdmin();
                //byte[] channelConfigSignature = fabClient.getChannelConfigurationSignature(channelConfig, ordererAdmin);
                byte[][] channelConfigSignaturesByPeerAdmin = new byte[peers.size()][];
                
                int i = 0;
                for (Peer peer : peers) {
                    User peerAdmin = getPeerAdmin(peer);
                    channelConfigSignaturesByPeerAdmin[i] = fabClient.getChannelConfigurationSignature(channelConfig, peerAdmin);
                    i++;
                }
                Channel channel = fabClient.newChannel(config.channelId, ord, channelConfig, channelConfigSignaturesByPeerAdmin);

                // join first peer
                for (Peer peer : peers) {
                    User peerAdmin = getPeerAdmin(peer);
                    fabClient.setUserContext(peerAdmin);
                    channel.joinPeer(peer);
                }

                fabChannel = channel.initialize();
                
            }
            
            // set user context to admin of peer0
            fabClient.setUserContext(getPeerAdmin(config.peers.get(0)));

        } catch (Throwable t) {
            if (RuntimeException.class.isAssignableFrom(t.getClass())) {
                throw (RuntimeException)t;
            } else {
                throw new FabricContextException(t);
            }
        }
        
    }

    public HFClient getClient() {
        return fabClient;
    }

    public HFCAClient getCAClient() {
        return fabCAClient;
    }

    public Channel getChannel() {
        return fabChannel;
    }
    
    // for JSON
    private static class ChaincodeMetaConfig {
        String id;
        String version;
        String policy; // chaincode endorsement policy YAML, relative to location of config
        String type;
        //
        public ChaincodeMetaConfig() {
            //
        }
    }
    
    public void ensureChaincode() {
        try {

            long CC_PROPOSAL_WAIT_TIME = 120000; // in ms

            //
            // read in the meta config
            String metaString = new String(Files.readAllBytes(Paths.get(config.chaincode.meta)), StandardCharsets.UTF_8);
            String metaDir = new File(config.chaincode.meta).getParent();
            ChaincodeMetaConfig meta = new JsonParser().parse(metaString, ChaincodeMetaConfig.class);
            String ccLabel = String.format("%s:%s", meta.id, meta.version);
            ccMetaId = meta.id;
            ccMetaVersion = meta.version;

            debugOut("Read chaincode meta from %s, chaincode = %s : version %s", config.chaincode.meta, ccMetaId, ccMetaVersion);

            TransactionRequest.Type ccMetaType;
            if (Strings.isNullOrEmpty(meta.type) || meta.type.compareToIgnoreCase("golang") == 0)
                ccMetaType = TransactionRequest.Type.GO_LANG;
            else if (meta.type.compareToIgnoreCase("java") == 0)
                ccMetaType = TransactionRequest.Type.JAVA;
            else if (meta.type.compareToIgnoreCase("node") == 0)
                ccMetaType = TransactionRequest.Type.NODE;
            else throw new FabricContextException(String.format("Invalid chaincode type '%s'", meta.type));
            
            Collection<Peer> peers = fabChannel.getPeers();
            for (Peer peer : peers) {
            
                User peerAdmin = getPeerAdmin(peer);
                fabClient.setUserContext(peerAdmin);
                
                // We may use v2.0 lifecycle, but it seems mutually incompatible with Idemix+x509 MSP configuration.
                // Alternatively it's compatible but not documented enough to work.
                
                debugOut("Ensuring installed chaincode on peer [%s]", peer.getName());
                List<Peer> singlePeerList = new LinkedList<Peer>(); // for proposals
                singlePeerList.add(peer);

                // query defined chaincodes
                boolean needInstall = true;
                boolean needUpgrade = false;
                List<Query.ChaincodeInfo> installedChaincodes = fabClient.queryInstalledChaincodes(peer);
                for (Query.ChaincodeInfo info : installedChaincodes) {
                    if (info.getName().equals(meta.id)) {
                        if (info.getVersion().equals(meta.version)) {
                            needInstall = false;
                            needUpgrade = false;
                            break;
                        }

                        needUpgrade = true;
                    }
                }

                // so we do it
                if (needInstall) {

                    if (needUpgrade) {

                        debugOut("Upgrading chaincode %s : to version %s", meta.id, meta.version);

                    } else {

                        debugOut("Installing chaincode %s : version %s", meta.id, meta.version);

                    }

                    ChaincodeID chaincodeId = ChaincodeID.newBuilder().setName(meta.id).setVersion(meta.version).setPath(config.chaincode.entryPath).build();
                    InstallProposalRequest ccInstall_req = fabClient.newInstallProposalRequest();
                    ccInstall_req.setChaincodeID(chaincodeId);
                    ccInstall_req.setChaincodeSourceLocation(new File(config.chaincode.gopath));
                    ccInstall_req.setChaincodeLanguage(ccMetaType);
                    // id is enough?
                    ccInstall_req.setProposalWaitTime(CC_PROPOSAL_WAIT_TIME);
                    ProposalResponse ccInstall_response = fabClient.sendInstallProposal(ccInstall_req, singlePeerList).iterator().next();
                    // installed?
                    if (ccInstall_response.getStatus() != Status.SUCCESS) {
                        throw new FabricContextException(ccInstall_response.getProposalResponse().toString());
                    }

                }
                
                debugOut("Ensuring instantiated chaincode on peer [%s]", peer.getName());

                // check if chaincode is instantiated
                boolean needInstantiate = true;
                boolean needInit = true;
                List<Query.ChaincodeInfo> queryInstantiated_response = fabChannel.queryInstantiatedChaincodes(peer);
                for (Query.ChaincodeInfo ccInstantiated : queryInstantiated_response) {
                    String instLabel = String.format("%s:%s", ccInstantiated.getName(), ccInstantiated.getVersion());
                    if (instLabel.equals(ccLabel)) {
                        needInstantiate = false;
                        needInit = false;
                        break;
                    } else if (instLabel.startsWith(meta.id+":")) {
                        needInit = false;
                    }
                }

                // this is strange. for some reason queryInstantiatedChaincodes is not very reliable...
                // to-do: look at Fabric docs for the new flow and find matching call, but reliable
                if (needInstantiate) {

                    if (needInit) {
                        debugOut("Instantiating new chaincode %s", ccLabel);
                    } else {
                        debugOut("Instantiating chaincode %s", ccLabel);
                    }

                    InstantiateProposalRequest ccInstantiate_req = fabClient.newInstantiationProposalRequest();
                    ccInstantiate_req.setProposalWaitTime(CC_PROPOSAL_WAIT_TIME);
                    ccInstantiate_req.setChaincodeID(ChaincodeID.newBuilder().setName(meta.id).setVersion(meta.version).build());
                    ccInstantiate_req.setArgs(new String[]{});
                    if (needInit) {
                        ChaincodeEndorsementPolicy policy = new ChaincodeEndorsementPolicy();
                        policy.fromYamlFile(new File(config.endorsementPolicy));
                        ccInstantiate_req.setChaincodeEndorsementPolicy(policy);
                        ccInstantiate_req.setFcn("Init");
                    }
                    Collection<ProposalResponse> ccInstantiate_responses = fabChannel.sendInstantiationProposal(ccInstantiate_req, singlePeerList);
                    ProposalResponse rsp = ccInstantiate_responses.iterator().next();
                    if (rsp.getStatus() != Status.SUCCESS) {
                        throw new FabricContextException(String.format("Chaincode instantiation failed with code %d (%s)", rsp.getStatus().getStatus(), rsp.getMessage()));
                    }
                    fabChannel.sendTransaction(ccInstantiate_responses).join();

                }
                
            }
            
            // set user context to admin of peer0
            fabClient.setUserContext(getPeerAdmin(config.peers.get(0)));
        
        } catch (Throwable t) {
            if (RuntimeException.class.isAssignableFrom(t.getClass())) {
                throw (RuntimeException)t;
            } else {
                throw new FabricContextException(t);
            }
        }
        
    }
    
    private String makeErrorFromProposalResponse(ProposalResponse rsp) {
        FabricProposalResponse.ProposalResponse rsp2 = rsp.getProposalResponse();
        if (rsp2 != null) {
            return rsp2.toString();
        }
        
        int status = rsp.getStatus().getStatus();
        String message = rsp.getMessage();
        return String.format("Chaincode returned status %d (%s)", status, message);
    }
    
    private byte[][] convertChaincodeArgs(String[] args) {
        byte[][] byteArgs = new byte[args.length][];
        for (int i = 0; i < args.length; i++) {
            byteArgs[i] = args[i].getBytes(StandardCharsets.UTF_8);
        }
        return byteArgs;
    }
    
    public byte[] queryChaincode(String fcn, String... args) {
        return queryChaincode(fcn, convertChaincodeArgs(args));
    }
    
    public byte[] queryChaincode(String fcn, byte[]... args) {
        
        try {
            
            QueryByChaincodeRequest req = fabClient.newQueryProposalRequest();
            req.setChaincodeID(ChaincodeID.newBuilder().setName(ccMetaId).setVersion(ccMetaVersion).build());
            req.setFcn(fcn);
            req.setArgs(args);
            List<Peer> singlePeerList = new LinkedList<Peer>();
            singlePeerList.add(fabChannel.getPeers().iterator().next());
            Collection<ProposalResponse> responses = fabChannel.queryByChaincode(req, singlePeerList);
            ProposalResponse rsp = responses.iterator().next();
            // check if status is not success
            if (rsp.getStatus() != Status.SUCCESS) {
                throw new FabricContextException(makeErrorFromProposalResponse(rsp));
            }
            byte[] result = rsp.getChaincodeActionResponsePayload();
            return result;
            
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            if (RuntimeException.class.isAssignableFrom(t.getClass())) {
                throw (RuntimeException)t;
            } else {
                throw new FabricContextException(t);
            }
        }
        
    }
    
    public byte[] queryChaincode(String fcn) {
        return queryChaincode(fcn, new String[]{});
    }
    
    public byte[] invokeChaincode(String fcn, String... args) {
        return invokeChaincode(fcn, convertChaincodeArgs(args));
    }
    
    public byte[] invokeChaincode(String fcn, byte[]... args) {
        
        try {
            
            long CC_PROPOSAL_WAIT_TIME = 30000;
            
            TransactionProposalRequest ccInit_req = fabClient.newTransactionProposalRequest();
            ccInit_req.setChaincodeID(ChaincodeID.newBuilder().setName(ccMetaId).setVersion(ccMetaVersion).build());
            ccInit_req.setFcn(fcn);
            ccInit_req.setArgs(args);
            if (fcn.compareToIgnoreCase("init") == 0) {
                ccInit_req.setProposalWaitTime(CC_PROPOSAL_WAIT_TIME);
            }
            
            // no args
            byte[] result = null; // should be exactly one, even though we are iterating.
            Collection<ProposalResponse> ccInit_responses = fabChannel.sendTransactionProposal(ccInit_req);
            for (ProposalResponse rsp : ccInit_responses) {
                if (rsp.getStatus() != Status.SUCCESS) {
                    throw new FabricContextException(makeErrorFromProposalResponse(rsp));
                } else {
                    if (result == null) {
                        result = rsp.getChaincodeActionResponsePayload();
                    } else {
                        byte[] localResult = rsp.getChaincodeActionResponsePayload();
                        if (!Arrays.equals(result, localResult)) {
                            throw new FabricContextException("Different peers returned different proposal response for Invoke");
                        }
                    }
                }
            }
            
            // all ok
            fabChannel.sendTransaction(ccInit_responses);
            return result;
            
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            if (RuntimeException.class.isAssignableFrom(t.getClass())) {
                throw (RuntimeException)t;
            } else {
                throw new FabricContextException(t);
            }
        }

    }
    
    public byte[] invokeChaincode(String fcn) {
        return invokeChaincode(fcn, new String[]{});
    }
    
    public byte[] querySystemChaincode(String cc, String fcn, String... args) {
        
        try {
            
            QueryByChaincodeRequest req = fabClient.newQueryProposalRequest();
            req.setChaincodeID(ChaincodeID.newBuilder().setName(cc).build());
            req.setFcn(fcn);
            req.setArgs(args);
            Collection<ProposalResponse> responses = fabChannel.queryByChaincode(req);
            ProposalResponse rsp = responses.iterator().next();
            // check if status is not success
            if (rsp.getStatus() != Status.SUCCESS) {
                throw new FabricContextException(makeErrorFromProposalResponse(rsp));
            }
            byte[] result = rsp.getChaincodeActionResponsePayload();
            return result;
            
        } catch (Throwable t) {
            if (RuntimeException.class.isAssignableFrom(t.getClass())) {
                throw (RuntimeException)t;
            } else {
                throw new FabricContextException(t);
            }
        }
        
    }
    
    public void shutdown() {
        fabChannel.shutdown(true);
        fabChannel = null;
        fabClient = null;
        fabCAClient = null;
    }

}
