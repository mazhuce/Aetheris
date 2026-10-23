package com.aetheris.ingestion.fixedexecution;

import com.aetheris.security.servlet.TrustedUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Immutable invocation, built from an already verified user or an explicitly bound DB cron actor. */
public final class IngestionActor {
    private final String kind,username,requestId;private final long uid,accessVersion;
    private IngestionActor(String kind,long uid,String username,long av,String requestId) {
        if(uid<=0||av<0||username==null||username.isEmpty()||username.length()>64||requestId==null||!requestId.matches("[A-Za-z0-9._:-]{1,128}"))throw FixedJson.invalid();
        username.codePoints().forEach(p->{if(p=='|'||Character.isISOControl(p)||Character.isWhitespace(p)||Character.isSpaceChar(p)||Character.getType(p)==Character.FORMAT||(p>=0xD800&&p<=0xDFFF))throw FixedJson.invalid();});
        this.kind=kind;this.uid=uid;this.username=username;this.accessVersion=av;this.requestId=requestId;
    }
    public static IngestionActor manual(TrustedUser user){if(user==null)throw new IllegalStateException("需要已验签执行主体");var p=user.proof();return new IngestionActor("manual",p.uid(),p.username(),p.accessVersion(),p.requestId());}
    static IngestionActor cron(JsonNode n){FixedJson.fields(n,"kind","uid","username","accessVersion","requestId");if(!"cron".equals(FixedJson.text(n,"kind")))throw FixedJson.invalid();return new IngestionActor("cron",FixedJson.number(n,"uid",1),FixedJson.text(n,"username"),FixedJson.number(n,"accessVersion",0),FixedJson.text(n,"requestId"));}
    public IngestionActor forCron(){return new IngestionActor("cron",uid,username,accessVersion,requestId);}
    public ObjectNode json(){return FixedJson.JSON.createObjectNode().put("kind",kind).put("uid",uid).put("username",username).put("accessVersion",accessVersion).put("requestId",requestId);}
    public String username(){return username;}public long uid(){return uid;}public long accessVersion(){return accessVersion;}
}
