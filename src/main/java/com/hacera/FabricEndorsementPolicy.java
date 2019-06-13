// (c) 2019 The Unbounded Network LTD
package com.hacera;

import io.netty.util.internal.StringUtil;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hyperledger.fabric.protos.common.MspPrincipal.MSPPrincipal;
import org.hyperledger.fabric.protos.common.MspPrincipal.MSPRole;
import org.hyperledger.fabric.protos.common.Policies;
import org.hyperledger.fabric.protos.common.Policies.SignaturePolicy;
import org.hyperledger.fabric.sdk.exception.ChaincodeEndorsementPolicyParseException;
import org.yaml.snakeyaml.Yaml;

public class FabricEndorsementPolicy {
    
    @SuppressWarnings ("serial")
    private static class IndexedHashMap<K, V> extends LinkedHashMap<K, V> {
        final HashMap<K, Integer> kmap = new HashMap<K, Integer>();

        @Override
        public V put(K key, V value) {
            kmap.put(key, size());
            return super.put(key, value);
        }

        Integer getKeysIndex(String n) {
            return kmap.get(n);
        }
    }
    
    // these are from ChaincodeEndorsementPolicy in JSDK, but they are private there
    private static final Pattern noofPattern = Pattern.compile("^(\\d+)-of$");
    
    private static SignaturePolicy parsePolicy(IndexedHashMap<String, MSPPrincipal> identities, Map<?, ?> mp) throws ChaincodeEndorsementPolicyParseException {

        if (mp == null) {
            throw new ChaincodeEndorsementPolicyParseException("No policy section was found in the document.");
        }
        if (!(mp instanceof Map)) {
            throw new ChaincodeEndorsementPolicyParseException("Policy expected object section was not found in the document.");

        }

        for (Map.Entry<?, ?> ks : mp.entrySet()) {
            Object ko = ks.getKey();
            Object vo = ks.getValue();
            final String key = (String) ko;
            // String val = (String) vo;

            if ("signed-by".equals(key)) {

                if (!(vo instanceof String)) {
                    throw new ChaincodeEndorsementPolicyParseException("signed-by expecting a string value");
                }

                MSPPrincipal mspPrincipal = identities.get(vo);
                if (null == mspPrincipal) {
                    throw new ChaincodeEndorsementPolicyParseException(String.format("No identity found by name %s in signed-by.", vo));
                }

                return SignaturePolicy.newBuilder()
                        .setSignedBy(identities.getKeysIndex((String) vo))
                        .build();

            } else {

                Matcher match = noofPattern.matcher(key);

                if (match.matches() && match.groupCount() == 1) {

                    String matchStingNo = match.group(1).trim();
                    int matchNo = Integer.parseInt(matchStingNo);

                    if (!(vo instanceof List)) {
                        throw new ChaincodeEndorsementPolicyParseException(String.format("%s expected to have list but found %s.", key, String.valueOf(vo)));
                    }

                    @SuppressWarnings ("unchecked") final List<Map<?, ?>> voList = (List<Map<?, ?>>) vo;

                    if (voList.size() < matchNo) {

                        throw new ChaincodeEndorsementPolicyParseException(String.format("%s expected to have at least %d items to match but only found %d.", key, matchNo, voList.size()));
                    }

                    SignaturePolicy.NOutOf.Builder spBuilder = SignaturePolicy.NOutOf.newBuilder()
                            .setN(matchNo);

                    for (Map<?, ?> nlo : voList) {

                        SignaturePolicy sp = parsePolicy(identities, nlo);
                        spBuilder.addRules(sp);

                    }

                    return SignaturePolicy.newBuilder().setNOutOf(spBuilder.build()).build();

                } else {

                    throw new ChaincodeEndorsementPolicyParseException(String.format("Unsupported policy type %s", key));
                }

            }

        }
        throw new ChaincodeEndorsementPolicyParseException("No values found for policy");

    }

    private static IndexedHashMap<String, MSPPrincipal> parseIdentities(Map<?, ?> identities) throws ChaincodeEndorsementPolicyParseException {
        //Only Role types are excepted at this time.

        IndexedHashMap<String, MSPPrincipal> ret = new IndexedHashMap<String, MSPPrincipal>();

        for (Map.Entry<?, ?> kp : identities.entrySet()) {
            Object key = kp.getKey();
            Object val = kp.getValue();

            if (!(key instanceof String)) {
                throw new ChaincodeEndorsementPolicyParseException(String.format("In identities key expected String got %s ", key == null ? "null" : key.getClass().getName()));
            }

            if (null != ret.get(key)) {
                throw new ChaincodeEndorsementPolicyParseException(String.format("In identities with key %s is listed more than once ", key));
            }

            if (!(val instanceof Map)) {
                throw new ChaincodeEndorsementPolicyParseException(String.format("In identities with key %s value expected Map got %s ", key, val == null ? "null" : val.getClass().getName()));
            }

            Object role = ((Map<?, ?>) val).get("role");

            if (!(role instanceof Map)) {
                throw new ChaincodeEndorsementPolicyParseException(String.format("In identities with key %s value expected Map for role got %s ", key, role == null ? "null" : role.getClass().getName()));
            }
            final Map<?, ?> roleMap = (Map<?, ?>) role;

            Object nameObj = (roleMap).get("name");

            if (!(nameObj instanceof String)) {
                throw new ChaincodeEndorsementPolicyParseException(String.format("In identities with key %s name expected String in role got %s ",
                        key, nameObj == null ? "null" : nameObj.getClass().getName()));
            }
            String name = (String) nameObj;
            name = name.trim();
            Object mspId = roleMap.get("mspId");

            if (!(mspId instanceof String)) {
                throw new ChaincodeEndorsementPolicyParseException(String.format("In identities with key %s mspId expected String in role got %s ", key, mspId == null ? "null" : mspId.getClass().getName()));
            }

            if (StringUtil.isNullOrEmpty((String) mspId)) {

                throw new ChaincodeEndorsementPolicyParseException(String.format("In identities with key %s mspId must not be null or empty String in role ", key));

            }

            MSPRole.MSPRoleType mspRoleType;

            if (name.compareToIgnoreCase("member") == 0) {
                mspRoleType = MSPRole.MSPRoleType.MEMBER;
            } else if (name.compareToIgnoreCase("admin") == 0) {
                mspRoleType = MSPRole.MSPRoleType.ADMIN;
            } else if (name.compareToIgnoreCase("client") == 0) {
                mspRoleType = MSPRole.MSPRoleType.CLIENT;
            } else if (name.compareToIgnoreCase("peer") == 0) {
                mspRoleType = MSPRole.MSPRoleType.PEER;
            } else {
                throw new ChaincodeEndorsementPolicyParseException(String.format("In identities with key %s name expected member, admin, client, or peer in role got %s ", key, name));
            }

            MSPRole mspRole = MSPRole.newBuilder().setRole(mspRoleType)
                    .setMspIdentifier((String) mspId).build();

            MSPPrincipal principal = MSPPrincipal.newBuilder()
                    .setPrincipalClassification(MSPPrincipal.Classification.ROLE)
                    .setPrincipal(mspRole.toByteString()).build();

            ret.put((String) key, principal);

        }

        if (ret.size() == 0) {
            throw new ChaincodeEndorsementPolicyParseException("No identities were found in the policy specification");
        }

        return ret;

    }
    
    public static byte[] bytesFromYamlFile(File yamlPolicyFile) throws IOException, ChaincodeEndorsementPolicyParseException {
        final Yaml yaml = new Yaml();
        final Map<?, ?> load = (Map<?, ?>) yaml.load(new FileInputStream(yamlPolicyFile));

        Map<?, ?> mp = (Map<?, ?>) load.get("policy");

        if (null == mp) {
            throw new ChaincodeEndorsementPolicyParseException("The policy file has no policy section");
        }

        IndexedHashMap<String, MSPPrincipal> identities = parseIdentities((Map<?, ?>) load.get("identities"));
        
        SignaturePolicy sp = parsePolicy(identities, mp);
        
        byte[] policyBytes = Policies.SignaturePolicyEnvelope.newBuilder()
                .setVersion(0)
                .addAllIdentities(identities.values())
                .setRule(sp)
                .build().toByteArray();
        
        return policyBytes;
    }
    
}
