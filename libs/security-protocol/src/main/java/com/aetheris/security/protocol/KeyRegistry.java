package com.aetheris.security.protocol;

import java.security.PublicKey;
import java.util.*;

/** Immutable operator-owned registry. Registering a key is a trust/configuration action. */
public final class KeyRegistry {
    public record Entry(String keyId,KeyPurpose purpose,String caller,PublicKey publicKey){ }
    private final Map<String,Entry> entries;
    public KeyRegistry(Collection<Entry> configured){
        Rules.require(configured!=null && !configured.isEmpty() && configured.size()<=256,"Missing or oversized public key registry");
        Map<String,Entry> byId=new HashMap<>();Map<String,Entry> byKey=new HashMap<>();
        for(Entry entry:configured){
            Rules.require(entry!=null && entry.purpose()!=null && entry.publicKey()!=null,"Incomplete public key entry");
            Rules.kid(entry.keyId());Rules.id(entry.caller());
            entry=new Entry(entry.keyId(),entry.purpose(),entry.caller(),KeyMaterial.canonicalPublicKey(entry.publicKey()));
            Rules.require(entry.purpose()!=KeyPurpose.USER || "gateway".equals(entry.caller()),"User issuer must be gateway");
            Rules.require(entry.publicKey().getEncoded()!=null,"Unencoded public key");
            String fingerprint=BodyHash.sha256(entry.publicKey().getEncoded());
            Entry existing=byKey.putIfAbsent(fingerprint,entry);
            Rules.require(existing==null || (existing.purpose()==entry.purpose() && existing.caller().equals(entry.caller())),"Public key cannot cross purpose or caller");
            Rules.require(byId.putIfAbsent(entry.keyId(),entry)==null,"Duplicate public key identifier");
        }
        entries=Map.copyOf(byId);
    }
    public Entry require(String keyId){Rules.kid(keyId);Entry entry=entries.get(keyId);Rules.require(entry!=null,"Unknown public key identifier");return entry;}
}
