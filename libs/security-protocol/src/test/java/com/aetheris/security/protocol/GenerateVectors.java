package com.aetheris.security.protocol;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Manual fixture authoring only. NEVER use these checked-in private keys in an installation. */
public final class GenerateVectors {
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected fixture output path");
        var generator=KeyPairGenerator.getInstance("Ed25519");
        KeyPair user=generator.generateKeyPair(),workflow=generator.generateKeyPair(),metadata=generator.generateKeyPair();
        String body="{\"id\":1}";
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        String actor="{\"version\":2,\"issuer\":\"gateway\",\"audience\":\"workflow\",\"uid\":7,\"username\":\"alice\",\"accessVersion\":3,\"issuedAt\":1800000000,\"expiresAt\":1800000120,\"requestId\":\"request-1\"}";
        String request="{\"version\":2,\"caller\":\"workflow\",\"audience\":\"metadata\",\"method\":\"POST\",\"requestTarget\":\"/internal/v2/executions/sql\",\"rawBodySha256\":\""+hash+"\",\"nonce\":\"AQIDBAUGBwgJCgsMDQ4PEA\",\"issuedAt\":1800000000,\"expiresAt\":1800000030,\"operation\":\"execution.sql\",\"userProofSha256\":\""+BodyHash.userProof(null)+"\"}";
        String response="{\"version\":2,\"receiver\":\"metadata\",\"caller\":\"workflow\",\"requestNonce\":\"AQIDBAUGBwgJCgsMDQ4PEA\",\"status\":200,\"rawBodySha256\":\""+hash+"\",\"issuedAt\":1800000000,\"expiresAt\":1800000030}";
        String fixture="{\n  \"warning\":\"PUBLIC TEST FIXTURE ONLY - NEVER INSTALL THESE PRIVATE KEYS\",\n  \"epochSecond\":1800000000,\n"
                +"  \"gateway\":"+keys(user)+",\n  \"workflow\":"+keys(workflow)+",\n  \"metadata\":"+keys(metadata)+",\n"
                +"  \"rawBodyBase64url\":\""+base(body.getBytes(StandardCharsets.UTF_8))+"\",\n"
                +"  \"user\":\""+sign(user,"gateway-1","AETHERIS-USER-V2",actor)+"\",\n"
                +"  \"request\":\""+sign(workflow,"workflow-1","AETHERIS-REQUEST-V2",request)+"\",\n"
                +"  \"response\":\""+sign(metadata,"metadata-1","AETHERIS-RESPONSE-V2",response)+"\"\n}\n";
        Path output=Path.of(args[0]);if(Files.exists(output))throw new IllegalStateException("Refusing to overwrite frozen fixture");Files.writeString(output,fixture,StandardCharsets.UTF_8);
    }
    static String keys(KeyPair pair){return "{\"publicKeyX509Base64\":\""+Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())+"\",\"privateKeyPkcs8Base64\":\""+Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())+"\"}";}
    static String base(byte[] bytes){return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    static String sign(KeyPair pair,String kid,String typ,String payload)throws Exception {
        String header="{\"alg\":\"EdDSA\",\"typ\":\""+typ+"\",\"kid\":\""+kid+"\"}";
        String input=base(header.getBytes(StandardCharsets.UTF_8))+"."+base(payload.getBytes(StandardCharsets.UTF_8));
        Signature signature=Signature.getInstance("Ed25519");signature.initSign(pair.getPrivate());signature.update(input.getBytes(StandardCharsets.US_ASCII));return input+"."+base(signature.sign());
    }
}
