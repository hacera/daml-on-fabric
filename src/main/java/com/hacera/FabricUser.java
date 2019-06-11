// (c) 2019 The Unbounded Network LTD
package com.hacera;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Set;
import jodd.json.JsonParser;
import jodd.json.JsonSerializer;
import org.apache.milagro.amcl.FP256BN.BIG;
import org.hyperledger.fabric.protos.idemix.Idemix;
import org.hyperledger.fabric.sdk.Enrollment;
// (c) 2019 HACERA
import org.hyperledger.fabric.sdk.User;
import org.hyperledger.fabric.sdk.idemix.IdemixCredential;
import org.hyperledger.fabric.sdk.idemix.IdemixIssuerPublicKey;
import org.hyperledger.fabric.sdk.idemix.IdemixUtils;
import org.hyperledger.fabric.sdk.identity.IdemixEnrollment;
import org.hyperledger.fabric.sdk.identity.X509Enrollment;

// FabricUser represents a User record with some predefined properties.
public class FabricUser implements User {

    private final String userName; // Admin
    private final String userAffiliation; // org1.example.com
    private final Enrollment userEnrollment; // 
    private final String userMspID; // Org1MSP
    
    public FabricUser(String name, String affiliation, Enrollment enrollment, String mspid) {
        
        userName = name;
        userAffiliation = affiliation;
        userEnrollment = enrollment;
        userMspID = mspid;
        
    }
    
    public String getName() {
        return userName;
    }

    public Set<String> getRoles() {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    public String getAccount() {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    public String getAffiliation() {
        return userAffiliation;
    }

    public Enrollment getEnrollment() {
        return userEnrollment;
    }

    public String getMspId() {
        return userMspID;
    }
    
    // json structure
    private static class JsonFabricUser {
        public String name;
        public String affiliation;
        public String enrollment;
        public String mspid;
        public String type;
    }
    
    // this too
    private static class SerializableIdemixEnrollment implements Serializable {
        
        public String ipk;
        public String revpk;
        public String mspid;
        public String sk;
        public String cred;
        public String cri;
        public String ou;
        public int rolemask;
        
        public SerializableIdemixEnrollment(IdemixEnrollment e) {
            
            Idemix.IssuerPublicKey protoIpk = grossHackForIpk(e.getIpk());
            ipk = Base64.getEncoder().encodeToString(protoIpk.toByteArray());
            revpk = Base64.getEncoder().encodeToString(e.getRevocationPk().getEncoded());
            mspid = e.getMspId();
            sk = Base64.getEncoder().encodeToString(IdemixUtils.bigToBytes(e.getSk()));
            Idemix.Credential protoCred = grossHackForCredential(e.getCred());
            cred = Base64.getEncoder().encodeToString(protoCred.toByteArray());
            cri = Base64.getEncoder().encodeToString(e.getCri().toByteArray()); // at least one thing done well
            ou = e.getOu();
            rolemask = e.getRoleMask();
            
        }
        
        public IdemixEnrollment getIdemixEnrollment() {
            
            //public IdemixEnrollment(IdemixIssuerPublicKey ipk, PublicKey revocationPk, String mspId, BIG sk, IdemixCredential cred, CredentialRevocationInformation cri, String ou, int roleMask) {
            try {
                
                Idemix.IssuerPublicKey protoIpk = Idemix.IssuerPublicKey.parseFrom(Base64.getDecoder().decode(ipk));
                IdemixIssuerPublicKey e_ipk = new IdemixIssuerPublicKey(protoIpk);
                PublicKey e_revpk = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(revpk)));
                BIG e_sk = BIG.fromBytes(Base64.getDecoder().decode(sk));
                Idemix.Credential protoCred = Idemix.Credential.parseFrom(Base64.getDecoder().decode(cred));
                IdemixCredential e_cred = new IdemixCredential(protoCred);
                Idemix.CredentialRevocationInformation e_cri = Idemix.CredentialRevocationInformation.parseFrom(Base64.getDecoder().decode(cri));
                return new IdemixEnrollment(e_ipk, e_revpk, mspid, e_sk, e_cred, e_cri, ou, rolemask);
                
            } catch (Throwable t) {
                if (RuntimeException.class.isAssignableFrom(t.getClass())) {
                    throw (RuntimeException) t;
                } else {
                    throw new RuntimeException(t);
                }
            }
            
        }
        
        private static Idemix.IssuerPublicKey grossHackForIpk(IdemixIssuerPublicKey ipk) {

            // to-do: fix this.........
            //        really, this is a design issue for the team who developed Idemix code.
            //        Idemix structures are not serializable in any way.
            try {

                Class c = ipk.getClass();
                Method toProto = c.getDeclaredMethod("toProto");
                toProto.setAccessible(true);
                Idemix.IssuerPublicKey protoIpk = (Idemix.IssuerPublicKey) toProto.invoke(ipk);
                return protoIpk;

            } catch (Throwable t) {
                if (RuntimeException.class.isAssignableFrom(t.getClass())) {
                    throw (RuntimeException) t;
                } else {
                    throw new RuntimeException(t);
                }
            }

        }
        
        private static Idemix.Credential grossHackForCredential(IdemixCredential cred) {
            
            // to-do: fix this.........
            //        really, this is a design issue for the team who developed Idemix code.
            //        Idemix structures are not serializable in any way.
            try {

                Class c = cred.getClass();
                Method toProto = c.getDeclaredMethod("toProto");
                toProto.setAccessible(true);
                Idemix.Credential protoCred = (Idemix.Credential) toProto.invoke(cred);
                return protoCred;

            } catch (Throwable t) {
                if (RuntimeException.class.isAssignableFrom(t.getClass())) {
                    throw (RuntimeException) t;
                } else {
                    throw new RuntimeException(t);
                }
            }
            
        }
        
    }
    
    // serialize serializes local user to JSON
    public String serializeJSON() throws IOException {
        
        // serialize stream first
        // serializing Idemix enrollment is a separate story... because it's not serializable ;)
        String enrollmentType;
        byte[] enrollmentBytes;
        if (X509Enrollment.class.isAssignableFrom(userEnrollment.getClass())) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(userEnrollment);
            enrollmentBytes = baos.toByteArray();
            enrollmentType = "x509";
        } else if (IdemixEnrollment.class.isAssignableFrom(userEnrollment.getClass())) {
            SerializableIdemixEnrollment wrapper = new SerializableIdemixEnrollment((IdemixEnrollment) userEnrollment);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(wrapper);
            enrollmentBytes = baos.toByteArray();
            enrollmentType = "idemix";
        } else {
            throw new RuntimeException(String.format("Unknown enrollment class type '%s'", userEnrollment.getClass().getCanonicalName()));
        }
        
        JsonFabricUser proxyUser = new JsonFabricUser();
        proxyUser.name = userName;
        proxyUser.affiliation = userAffiliation;
        proxyUser.mspid = userMspID;
        proxyUser.enrollment = Base64.getEncoder().encodeToString(enrollmentBytes);
        proxyUser.type = enrollmentType;
        
        String jsonString = JsonSerializer.create().serialize(proxyUser);
        return jsonString;
        
    }
    
    // unserializeJSON unserializes local user from JSON
    public static FabricUser unserializeJSON(String json) throws IOException, ClassNotFoundException {
        
        JsonFabricUser proxyUser = new JsonParser().parse(json, JsonFabricUser.class);
        // unserialize enrollment
        Enrollment enrollment;
        byte[] enrollmentBytes = Base64.getDecoder().decode(proxyUser.enrollment);
        if ("x509".equals(proxyUser.type)) {
            ByteArrayInputStream bais = new ByteArrayInputStream(enrollmentBytes);
            ObjectInputStream ois = new ObjectInputStream(bais);
            enrollment = (Enrollment) ois.readObject();
        } else if ("idemix".equals(proxyUser.type)) {
            ByteArrayInputStream bais = new ByteArrayInputStream(enrollmentBytes);
            ObjectInputStream ois = new ObjectInputStream(bais);
            SerializableIdemixEnrollment wrapper = (SerializableIdemixEnrollment) ois.readObject();
            enrollment = wrapper.getIdemixEnrollment();
        } else {
            throw new RuntimeException(String.format("Unknown enrollment type '%s'", proxyUser.type));
        }
        
        FabricUser user = new FabricUser(proxyUser.name, proxyUser.affiliation, enrollment, proxyUser.mspid);
        return user;
        
    }
    
}
