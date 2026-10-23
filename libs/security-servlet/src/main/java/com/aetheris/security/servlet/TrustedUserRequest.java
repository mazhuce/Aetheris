package com.aetheris.security.servlet;

import jakarta.servlet.http.*;
import java.security.Principal;
import java.util.*;

class TrustedUserRequest extends HttpServletRequestWrapper {
    private final TrustedUser user;
    TrustedUserRequest(HttpServletRequest request,TrustedUser user){super(request);this.user=user;}
    @Override public String getHeader(String name){return WorkloadProofFilter.hidden(name)?null:super.getHeader(name);}
    @Override public Enumeration<String> getHeaders(String name){return WorkloadProofFilter.hidden(name)?Collections.emptyEnumeration():super.getHeaders(name);}
    @Override public Enumeration<String> getHeaderNames(){return Collections.enumeration(Collections.list(super.getHeaderNames()).stream().filter(n->!WorkloadProofFilter.hidden(n)).toList());}
    @Override public int getIntHeader(String name){return WorkloadProofFilter.hidden(name)?-1:super.getIntHeader(name);}
    @Override public long getDateHeader(String name){return WorkloadProofFilter.hidden(name)?-1:super.getDateHeader(name);}
    @Override public Object getAttribute(String name){return TrustedUser.ATTRIBUTE.equals(name)?user:super.getAttribute(name);}
    @Override public Enumeration<String> getAttributeNames(){var names=new HashSet<>(Collections.list(super.getAttributeNames()));if(user!=null)names.add(TrustedUser.ATTRIBUTE);else names.remove(TrustedUser.ATTRIBUTE);return Collections.enumeration(names);}
    @Override public void setAttribute(String name,Object value){if(TrustedUser.ATTRIBUTE.equals(name))throw new IllegalStateException("Trusted identity is immutable");super.setAttribute(name,value);}
    @Override public void removeAttribute(String name){if(TrustedUser.ATTRIBUTE.equals(name))throw new IllegalStateException("Trusted identity is immutable");super.removeAttribute(name);}
    @Override public Cookie[] getCookies(){return null;}
    @Override public Principal getUserPrincipal(){return null;}
    @Override public String getRemoteUser(){return null;}
    @Override public String getAuthType(){return null;}
    @Override public boolean isUserInRole(String role){return false;}
    @Override public HttpSession getSession(boolean create){if(create)throw new IllegalStateException("Proof-authenticated sessions are disabled");return null;}
    @Override public HttpSession getSession(){return getSession(true);}
    @Override public String getRequestedSessionId(){return null;}
    @Override public boolean isRequestedSessionIdValid(){return false;}
    @Override public boolean isRequestedSessionIdFromCookie(){return false;}
    @Override public boolean isRequestedSessionIdFromURL(){return false;}
}
