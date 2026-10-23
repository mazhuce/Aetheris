package com.aetheris.security.protocol.configuration;

import com.aetheris.security.protocol.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolInstallationTest {
    @TempDir Path directory;
    private final ObjectMapper json=new ObjectMapper();
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-18T10:00:00Z"),ZoneOffset.UTC);
    private KeyPair user,workload;
    private Path registry,workloadFile,userFile;
    private List<Map<String,Object>> keys;
    private String pem(Key key,String kind){return "-----BEGIN "+kind+"-----\n"+Base64.getEncoder().encodeToString(key.getEncoded())+"\n-----END "+kind+"-----";}
    @BeforeEach void setup() throws Exception {
        var generator=KeyPairGenerator.getInstance("Ed25519");user=generator.generateKeyPair();workload=generator.generateKeyPair();
        keys=new ArrayList<>(List.of(entry("user-1","USER","gateway",user),entry("workflow-1","WORKLOAD","workflow",workload)));
        registry=directory.resolve("public.json");workloadFile=directory.resolve("workload.pem");userFile=directory.resolve("user.pem");
        Files.writeString(workloadFile,pem(workload.getPrivate(),"PRIVATE KEY"));Files.writeString(userFile,pem(user.getPrivate(),"PRIVATE KEY"));
        save();
    }
    private Map<String,Object> entry(String id,String purpose,String caller,KeyPair pair){return Map.of("kid",id,"purpose",purpose,"caller",caller,"publicKey",pem(pair.getPublic(),"PUBLIC KEY"));}
    private void save() throws Exception {Files.writeString(registry,json.writeValueAsString(Map.of("schemaVersion",1,"keys",keys)));}
    private ProtocolInstallation load(){return ProtocolInstallation.load("workflow",registry,"workflow-1",workloadFile,null,null,clock);}
    @Test void loadsOnlyOwnWorkloadKeyAndVerifiesGatewayWithoutUserSigningCapability() {
        var installation=load();long now=clock.instant().getEpochSecond();
        var proof=new WorkloadRequestProof(2,"workflow","metadata","POST","/internal/v2/executions/sql",BodyHash.sha256(new byte[0]),Nonce.random(),now,now+20,"execution.sql",BodyHash.userProof(null));
        String compact=installation.workloadSigner().sign(proof);
        assertEquals("workflow",installation.verifier().verifyRequest(compact,new ExpectedRequest("metadata","POST",proof.requestTarget(),proof.rawBodySha256(),proof.operation(),BodyHash.userProof(null))).caller());
        assertTrue(installation.userSigner().isEmpty());assertEquals(64,installation.registrySha256().length());
        assertFalse(installation.toString().contains("PRIVATE"));
    }
    @Test void gatewayRequiresSeparateOwnUserAndWorkloadKeys() throws Exception {
        var gateway=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        keys.add(entry("gateway-1","WORKLOAD","gateway",gateway));save();
        assertThrows(ProtocolException.class,()->ProtocolInstallation.load("gateway",registry,"gateway-1",workloadFile,null,null,clock));
        assertThrows(ProtocolException.class,()->ProtocolInstallation.load("workflow",registry,"workflow-1",workloadFile,"user-1",userFile,clock));
        Files.writeString(workloadFile,pem(gateway.getPrivate(),"PRIVATE KEY"));
        var installed=ProtocolInstallation.load("gateway",registry,"gateway-1",workloadFile,"user-1",userFile,clock);
        long now=clock.instant().getEpochSecond();
        var proof=new UserProof(2,"gateway","metadata",1,"admin",0,now,now+60,"request-1");
        assertEquals("admin",installed.verifier().verifyUser(installed.userSigner().orElseThrow().sign(proof),"metadata").proof().username());
    }
    @Test void refusesOtherCallerAndWrongPrivateKey() throws Exception {
        assertThrows(ProtocolException.class,()->ProtocolInstallation.load("metadata",registry,"workflow-1",workloadFile,null,null,clock));
        Files.writeString(workloadFile,pem(user.getPrivate(),"PRIVATE KEY"));assertThrows(ProtocolException.class,this::load);
    }
    @Test void rejectsUnknownDuplicateAndMissingRegistryFields() throws Exception {
        String valid=Files.readString(registry);
        for(String invalid:List.of(valid.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1"),
                valid.replace("\"keys\":","\"extra\":true,\"keys\":"),valid+"{}",valid.replace("\"schemaVersion\":1","\"schemaVersion\":\"1\""))) {
            Files.writeString(registry,invalid);assertThrows(ProtocolException.class,this::load);
        }
    }
    @Test void rejectsUnknownServiceAndNoGatewayIdentityKey() throws Exception {
        keys.set(0,entry("other","WORKLOAD","workflow",user));save();assertThrows(ProtocolException.class,this::load);
        keys.set(0,entry("user-1","USER","gateway",user));keys.set(1,entry("evil","WORKLOAD","unknown",workload));save();
        assertThrows(ProtocolException.class,this::load);
    }
    @Test void rejectsMissingOversizedAndMalformedFilesWithoutLeakingMaterial() throws Exception {
        Files.delete(workloadFile);assertThrows(ProtocolException.class,this::load);
        String secret="secret-value-do-not-log";Files.writeString(workloadFile,secret);
        assertFalse(assertThrows(ProtocolException.class,this::load).getMessage().contains(secret));
        Files.write(workloadFile,new byte[4097]);assertThrows(ProtocolException.class,this::load);
        Files.write(registry,new byte[]{(byte)0xc0,(byte)0xaf});assertThrows(ProtocolException.class,this::load);
    }
}
