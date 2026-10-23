package com.aetheris.security.protocol;

import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.util.*;

/** PEM input only; never includes supplied material or provider exceptions in errors. */
public final class KeyMaterial {
    private KeyMaterial(){ }
    public static PublicKey parseX509Pem(String pem){
        byte[] bytes=pem(pem,"PUBLIC KEY");
        try {PublicKey key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(bytes));requireEd25519(key);
            Rules.require(Arrays.equals(bytes,key.getEncoded()),"Noncanonical public key");return key;
        } catch(GeneralSecurityException error){throw new ProtocolException("Invalid Ed25519 public key");}
    }
    public static PrivateKey parsePkcs8Pem(String pem){
        byte[] bytes=pem(pem,"PRIVATE KEY");
        try {PrivateKey key=KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(bytes));requireEd25519(key);
            Rules.require(Arrays.equals(bytes,key.getEncoded()),"Noncanonical private key");return key;
        } catch(GeneralSecurityException error){throw new ProtocolException("Invalid Ed25519 private key");}
        finally {Arrays.fill(bytes,(byte)0);}
    }
    static void requireEd25519(Key key){
        boolean valid=(key instanceof EdECPublicKey publicKey && "Ed25519".equals(publicKey.getParams().getName()))
                ||(key instanceof EdECPrivateKey privateKey && "Ed25519".equals(privateKey.getParams().getName()));
        Rules.require(valid,"Expected Ed25519 key");
    }
    static PublicKey canonicalPublicKey(PublicKey supplied){
        requireEd25519(supplied);
        byte[] bytes=supplied.getEncoded();
        Rules.require(bytes!=null && bytes.length>=44 && bytes.length<=256,"Invalid encoded public key");
        try {
            PublicKey parsed=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(bytes));
            requireEd25519(parsed);Rules.require(Arrays.equals(bytes,parsed.getEncoded()),"Noncanonical public key");return parsed;
        }catch(GeneralSecurityException error){throw new ProtocolException("Invalid encoded Ed25519 public key");}
    }
    private static byte[] pem(String text,String label){
        Rules.require(text!=null && text.length()<=4096,"Missing or oversized PEM key");
        String start="-----BEGIN "+label+"-----",end="-----END "+label+"-----";
        String trimmed=text.strip();Rules.require(trimmed.startsWith(start) && trimmed.endsWith(end),"Invalid PEM envelope");
        String body=trimmed.substring(start.length(),trimmed.length()-end.length()).replace("\r","").replace("\n","");
        Rules.require(body.matches("[A-Za-z0-9+/]+={0,2}"),"Invalid PEM encoding");
        try {byte[] decoded=Base64.getDecoder().decode(body);Rules.require(decoded.length>=44 && decoded.length<=256
                    && Base64.getEncoder().encodeToString(decoded).equals(body),"Invalid PEM key length or encoding");return decoded;}
        catch(IllegalArgumentException error){throw new ProtocolException("Invalid PEM encoding");}
    }
}
