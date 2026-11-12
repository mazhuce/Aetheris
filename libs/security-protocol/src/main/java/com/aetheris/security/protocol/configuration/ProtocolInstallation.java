package com.aetheris.security.protocol.configuration;

import com.aetheris.security.protocol.*;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.Clock;
import java.security.PrivateKey;
import java.util.*;

/** Explicit file installation. No shared-token, environment-key, or test-key fallback. */
public final class ProtocolInstallation {
    public static final Set<String> SERVICES=Set.of("gateway","auth","system","metadata","serving","quality",
            "ingestion","governance","workflow","ai-agent","learning","mcp");
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(5).maxStringLength(4096).maxNumberLength(20).build())
            .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final String service;
    private final ProtocolVerifier verifier;
    private final ProtocolSigner workloadSigner;
    private final ProtocolSigner userSigner;
    private final String registrySha256;

    private ProtocolInstallation(String service,ProtocolVerifier verifier,ProtocolSigner workloadSigner,
                                 ProtocolSigner userSigner,String registrySha256) {
        this.service=service;this.verifier=verifier;this.workloadSigner=workloadSigner;
        this.userSigner=userSigner;this.registrySha256=registrySha256;
    }

    public static ProtocolInstallation load(String service,Path registryFile,String workloadKid,Path workloadPrivateFile,
                                            String userKid,Path userPrivateFile,Clock clock) {
        if(!SERVICES.contains(service) || clock==null)throw new ProtocolException("Unknown service installation");
        if(!"gateway".equals(service) && (userKid!=null || userPrivateFile!=null))
            throw new ProtocolException("Only gateway may install a user signing key");
        if("gateway".equals(service) && (userKid==null || userPrivateFile==null))
            throw new ProtocolException("Gateway user signing key is required");
        byte[] registryBytes=read(registryFile,256*1024);
        KeyRegistry registry=parseRegistry(registryBytes);
        TimePolicy policy=TimePolicy.defaults();
        ProtocolSigner workload=new ProtocolSigner(workloadKid,KeyPurpose.WORKLOAD,service,
                privateKey(workloadPrivateFile),registry,clock,policy);
        ProtocolSigner user=null;
        if("gateway".equals(service))user=new ProtocolSigner(userKid,KeyPurpose.USER,"gateway",
                privateKey(userPrivateFile),registry,clock,policy);
        return new ProtocolInstallation(service,new ProtocolVerifier(registry,clock,policy),workload,user,BodyHash.sha256(registryBytes));
    }

    public static ProtocolVerifier verifier(Path registryFile,Clock clock) {
        return new ProtocolVerifier(parseRegistry(read(registryFile,256*1024)),clock,TimePolicy.defaults());
    }

    private static KeyRegistry parseRegistry(byte[] raw) {
        try {
            JsonNode root=JSON.readTree(text(raw));
            fields(root,Set.of("schemaVersion","keys"));
            if(!root.get("schemaVersion").isIntegralNumber() || !root.get("schemaVersion").canConvertToInt()
                    || root.get("schemaVersion").intValue()!=1 || !root.get("keys").isArray()
                    || root.get("keys").isEmpty() || root.get("keys").size()>256)
                throw new ProtocolException("Invalid public registry structure");
            List<KeyRegistry.Entry> entries=new ArrayList<>();boolean gatewayUser=false;
            for(JsonNode item:root.get("keys")) {
                fields(item,Set.of("kid","purpose","caller","publicKey"));
                String caller=string(item,"caller");
                if(!SERVICES.contains(caller))throw new ProtocolException("Unknown registered service");
                KeyPurpose purpose=KeyPurpose.valueOf(string(item,"purpose"));
                entries.add(new KeyRegistry.Entry(string(item,"kid"),purpose,caller,
                        KeyMaterial.parseX509Pem(string(item,"publicKey"))));
                gatewayUser|=purpose==KeyPurpose.USER && "gateway".equals(caller);
            }
            if(!gatewayUser)throw new ProtocolException("Gateway public identity key is required");
            return new KeyRegistry(entries);
        } catch(IOException | IllegalArgumentException error) {
            throw new ProtocolException("Invalid public protocol registry");
        }
    }

    private static void fields(JsonNode node,Set<String> names) {
        if(node==null || !node.isObject() || node.size()!=names.size())throw new ProtocolException("Invalid registry fields");
        node.fieldNames().forEachRemaining(name -> {if(!names.contains(name))throw new ProtocolException("Unknown registry field");});
    }
    private static String string(JsonNode node,String name) {
        if(!node.path(name).isTextual())throw new ProtocolException("Invalid registry field type");
        return node.get(name).textValue();
    }
    private static byte[] read(Path path,int maximum) {
        if(path==null || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new ProtocolException("Missing regular protocol configuration file");
        try(InputStream stream=Files.newInputStream(path)) {
            byte[] value=stream.readNBytes(maximum+1);
            if(value.length==0 || value.length>maximum)throw new ProtocolException("Invalid protocol configuration size");
            return value;
        }catch(IOException error){throw new ProtocolException("Unable to read protocol configuration");}
    }
    private static String text(byte[] bytes) {
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException error){throw new ProtocolException("Invalid protocol configuration encoding");}
    }
    private static PrivateKey privateKey(Path path) {
        byte[] bytes=read(path,4096);
        try{return KeyMaterial.parsePkcs8Pem(text(bytes));}
        finally{Arrays.fill(bytes,(byte)0);}
    }

    public String service(){return service;}
    public ProtocolVerifier verifier(){return verifier;}
    public ProtocolSigner workloadSigner(){return workloadSigner;}
    public Optional<ProtocolSigner> userSigner(){return Optional.ofNullable(userSigner);}
    public String registrySha256(){return registrySha256;}
    @Override public String toString(){return "ProtocolInstallation[service="+service+"]";}
}
