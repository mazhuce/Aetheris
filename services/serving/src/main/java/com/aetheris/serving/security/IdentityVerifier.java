package com.aetheris.serving.security;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;

@Component
public class IdentityVerifier {
    public static final String HEADER = "X-Aetheris-Identity";
    private final byte[] token; private final long skew;
    public IdentityVerifier(@Value("${aetheris.internal-token:}") String token, @Value("${aetheris.identity.max-skew-ms:300000}") long skew){this.token=token==null?new byte[0]:token.getBytes(StandardCharsets.UTF_8);if(this.token.length>0&&this.token.length<32)throw new IllegalStateException("AETHERIS_INTERNAL_TOKEN must be at least 32 UTF-8 bytes");this.skew=skew;}
    public boolean configured(){return token.length>0;}
    public ServingIdentity verify(HttpServletRequest request){return request==null?null:verify(request.getHeader(HEADER));}
    public ServingIdentity verify(String value){
        if(!configured()||value==null||value.isBlank())return null;
        int dot=value.lastIndexOf('.');
        if(dot<=0||dot==value.length()-1)return null;
        String data=value.substring(0,dot);
        if(!constantEquals(hex(hmac(data)),value.substring(dot+1)))return null;
        try{
            String[] p=new String(Base64.getUrlDecoder().decode(data),StandardCharsets.UTF_8).split("\\|",-1);
            if(p.length!=3||p[1].isBlank()||Math.abs(Clock.systemUTC().millis()-Long.parseLong(p[2]))>skew)return null;
            Long uid="-".equals(p[0])?null:Long.valueOf(p[0]);
            if(uid!=null&&uid<=0)return null;
            return new ServingIdentity(uid,p[1]);
        }catch(RuntimeException e){return null;}
    }
    private byte[] hmac(String value){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(token,"HmacSHA256"));return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x));return s.toString();}
    private static boolean constantEquals(String a,String b){if(a.length()!=b.length())return false;int r=0;for(int i=0;i<a.length();i++)r|=a.charAt(i)^b.charAt(i);return r==0;}
}
