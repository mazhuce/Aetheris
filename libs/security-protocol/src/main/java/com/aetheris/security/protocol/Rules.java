package com.aetheris.security.protocol;

import java.time.Clock;
import java.util.Set;

final class Rules {
    static final String USER="AETHERIS-USER-V2",REQUEST="AETHERIS-REQUEST-V2",RESPONSE="AETHERIS-RESPONSE-V2";
    private static final long MAX_EPOCH=253402300799L;
    private static final Set<String> METHODS=Set.of("GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS");
    private Rules(){ }
    static void require(boolean valid,String message){if(!valid)throw new ProtocolException(message);}
    static void id(String value){require(value!=null && value.matches("[a-z][a-z0-9-]{0,63}"),"Invalid service identifier");}
    static void kid(String value){require(value!=null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"),"Invalid key identifier");}
    static void requestId(String value){require(value!=null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"),"Invalid request identifier");}
    static void operation(String value){require(value!=null && value.matches("[a-z][a-z0-9._-]{0,95}"),"Invalid operation");}
    static void hash(String value){require(value!=null && value.matches("[0-9a-f]{64}"),"Invalid body hash");}
    static void method(String value){require(METHODS.contains(value==null?"":value),"Invalid HTTP method");}
    static void status(int status){require(status>=100 && status<=599,"Invalid HTTP status");}
    static void path(String path){
        require(path!=null && path.length()<=1024 && path.startsWith("/internal/v2/")
                && !path.endsWith("/") && path.matches("/[A-Za-z0-9._~/-]+"),"Invalid canonical request target");
        for(String part:path.substring(1).split("/",-1))require(!part.isEmpty() && part.length()<=128 && !part.equals(".") && !part.equals(".."),"Invalid canonical request target");
    }
    static void nonce(String nonce){byte[] decoded=Codec.decode(nonce,86);require(decoded.length>=16 && decoded.length<=64,"Invalid nonce");}
    static void username(String username){
        require(username!=null && !username.isBlank() && username.length()<=128,"Invalid username");
        for(int offset=0;offset<username.length();) {
            int point=username.codePointAt(offset);
            require(!Character.isISOControl(point) && !Character.isWhitespace(point) && !Character.isSpaceChar(point)
                    && !(point>=0xD800 && point<=0xDFFF) && Character.getType(point)!=Character.FORMAT,"Invalid username");
            offset+=Character.charCount(point);
        }
    }
    static void times(long issued,long expires,long ttl,Clock clock,TimePolicy policy){
        require(clock!=null && policy!=null,"Missing clock or time policy");
        long now=clock.instant().getEpochSecond();
        require(now>0 && now<=MAX_EPOCH && issued>0 && issued<=MAX_EPOCH && expires>issued && expires<=MAX_EPOCH,"Invalid time range");
        require(expires-issued<=ttl && issued<=now+policy.futureSkewSeconds() && expires>now,"Proof expired or outside permitted time window");
    }
    static void user(UserProof p,Clock c,TimePolicy policy){
        require(p!=null && p.version()==2 && "gateway".equals(p.issuer()) && p.uid()>0 && p.accessVersion()>=0,"Invalid user claims");
        id(p.audience());username(p.username());requestId(p.requestId());times(p.issuedAt(),p.expiresAt(),policy.userMaxTtlSeconds(),c,policy);
    }
    static void request(WorkloadRequestProof p,Clock c,TimePolicy policy){
        require(p!=null && p.version()==2,"Invalid request claims");id(p.caller());id(p.audience());method(p.method());path(p.requestTarget());hash(p.rawBodySha256());hash(p.userProofSha256());nonce(p.nonce());operation(p.operation());times(p.issuedAt(),p.expiresAt(),policy.workloadMaxTtlSeconds(),c,policy);
    }
    static void response(WorkloadResponseProof p,Clock c,TimePolicy policy){
        require(p!=null && p.version()==2,"Invalid response claims");id(p.receiver());id(p.caller());nonce(p.requestNonce());status(p.status());hash(p.rawBodySha256());times(p.issuedAt(),p.expiresAt(),policy.workloadMaxTtlSeconds(),c,policy);
    }
}
