package com.aetheris.quality.security;
/** Retired compatibility type. No Spring bean, shared token, or fallback principal exists. */
@Deprecated
public final class ServiceIdentitySigner {
    public ServiceIdentitySigner(String ignoredToken,String ignoredPrincipal){}
    public String sign(){throw new IllegalStateException("旧质量服务身份签名已退休，必须使用封存运行引用");}
    String sign(String ignoredUser,long ignoredTime){return sign();}
}
