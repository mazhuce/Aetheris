package com.aetheris.security.protocol;

import org.junit.jupiter.api.*;
import java.security.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class KeyMaterialTest {
    static KeyPair pair;
    @BeforeAll static void setup()throws Exception{pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();}
    static String pem(String label,byte[] bytes){return "-----BEGIN "+label+"-----\n"+Base64.getEncoder().encodeToString(bytes)+"\n-----END "+label+"-----";}
    @Test void parsesStandardPkcs8AndX509AndCrLf(){
        assertArrayEquals(pair.getPrivate().getEncoded(),KeyMaterial.parsePkcs8Pem(pem("PRIVATE KEY",pair.getPrivate().getEncoded())).getEncoded());
        assertArrayEquals(pair.getPublic().getEncoded(),KeyMaterial.parseX509Pem(pem("PUBLIC KEY",pair.getPublic().getEncoded()).replace("\n","\r\n")).getEncoded());
    }
    @TestFactory Stream<DynamicTest> malformedKeysFailClosedWithoutEcho(){
        return Stream.of("", "SECRET-DO-NOT-ECHO", "x".repeat(4097),pem("PRIVATE KEY",new byte[10]),pem("PUBLIC KEY",pair.getPublic().getEncoded()),pem("PRIVATE KEY",pair.getPrivate().getEncoded())+"junk",pem("PRIVATE KEY",pair.getPrivate().getEncoded()).replace("\n"," "))
                .map(value->DynamicTest.dynamicTest("malformed length "+value.length(),()->{
                    var error=assertThrows(ProtocolException.class,()->KeyMaterial.parsePkcs8Pem(value));assertFalse(error.getMessage().contains("SECRET-DO-NOT-ECHO"));assertNull(error.getCause());
                }));
    }
    @Test void rejectsOtherAlgorithmsAndDerTrailingGarbage()throws Exception {
        KeyPair other=KeyPairGenerator.getInstance("Ed448").generateKeyPair();
        assertThrows(ProtocolException.class,()->KeyMaterial.parseX509Pem(pem("PUBLIC KEY",other.getPublic().getEncoded())));
        assertThrows(ProtocolException.class,()->KeyMaterial.parsePkcs8Pem(pem("PRIVATE KEY",other.getPrivate().getEncoded())));
        assertThrows(ProtocolException.class,()->KeyMaterial.parseX509Pem(pem("PUBLIC KEY",Arrays.copyOf(pair.getPublic().getEncoded(),pair.getPublic().getEncoded().length+1))));
        assertThrows(ProtocolException.class,()->KeyMaterial.parsePkcs8Pem(pem("PRIVATE KEY",Arrays.copyOf(pair.getPrivate().getEncoded(),pair.getPrivate().getEncoded().length+1))));
    }
    @Test void immutableRegistryRetainsSnapshotAndRejectsCrossCallerKeyReuse(){
        var entries=new ArrayList<>(List.of(new KeyRegistry.Entry("one",KeyPurpose.WORKLOAD,"workflow",pair.getPublic())));
        var registry=new KeyRegistry(entries);entries.clear();assertEquals("workflow",registry.require("one").caller());
        assertThrows(ProtocolException.class,()->new KeyRegistry(List.of(registry.require("one"),new KeyRegistry.Entry("two",KeyPurpose.WORKLOAD,"metadata",pair.getPublic()))));
    }
    @Test void rejectsMissingConfiguration(){
        assertThrows(ProtocolException.class,()->new ProtocolVerifier(null,ProtocolTest.CLOCK,TimePolicy.defaults()));
        assertThrows(ProtocolException.class,()->new TimePolicy(301,30,5));
        assertThrows(ProtocolException.class,()->new TimePolicy(120,61,5));
        assertThrows(ProtocolException.class,()->new TimePolicy(120,30,11));
        assertThrows(ProtocolException.class,()->KeyMaterial.parsePkcs8Pem(null));
        assertThrows(ProtocolException.class,()->KeyMaterial.parseX509Pem(null));
        assertThrows(ProtocolException.class,()->BodyHash.sha256(null));
    }
    @Test void registryRejectsMalformedEncodedPublicKeyAtStartup(){
        java.security.interfaces.EdECPublicKey malformed=new java.security.interfaces.EdECPublicKey(){
            public java.security.spec.EdECPoint getPoint(){return null;}
            public java.security.spec.NamedParameterSpec getParams(){return java.security.spec.NamedParameterSpec.ED25519;}
            public String getAlgorithm(){return "Ed25519";}
            public String getFormat(){return "X.509";}
            public byte[] getEncoded(){return new byte[10];}
        };
        assertThrows(ProtocolException.class,()->new KeyRegistry(List.of(new KeyRegistry.Entry("bad",KeyPurpose.WORKLOAD,"workflow",malformed))));
    }
}
