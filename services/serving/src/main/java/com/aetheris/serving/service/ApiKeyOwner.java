package com.aetheris.serving.service;

import com.aetheris.security.servlet.TrustedUser;
import com.aetheris.serving.security.ServingSecurity;

/** Persistent identity, never reconstructed from a username or refreshed during rotation. */
public record ApiKeyOwner(long uid,String username,long accessVersion) {
    public ApiKeyOwner {
        if(uid<=0||accessVersion<0||!validUsername(username))throw new ServingSecurity.UnauthorizedException("API key 身份绑定无效，请重新申请");
    }
    public static ApiKeyOwner from(TrustedUser user) {
        if(user==null)throw new ServingSecurity.UnauthorizedException("缺少有效的用户身份");
        var p=user.proof();return new ApiKeyOwner(p.uid(),p.username(),p.accessVersion());
    }
    private static boolean validUsername(String value) {
        if(value==null||value.isEmpty()||value.length()>64)return false;
        for(int i=0;i<value.length();) {
            int p=value.codePointAt(i);
            if(p=='|'||Character.isISOControl(p)||Character.isWhitespace(p)||Character.isSpaceChar(p)||Character.getType(p)==Character.FORMAT||p>=0xD800&&p<=0xDFFF)return false;
            i+=Character.charCount(p);
        }
        return true;
    }
}
