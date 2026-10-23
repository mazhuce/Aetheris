package com.aetheris.workflow.security;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.web.context.request.*;
import javax.crypto.Mac;import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;
class WorkflowV2BoundaryRegressionTest {
 static final String TOKEN="internal-test-token-with-enough-entropy";
 @Test void oldValidHmacCannotEstablishApiSubject() throws Exception {var request=new MockHttpServletRequest("GET","/api/workflow/tasks");String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(("1|admin|"+System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(TOKEN.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));request.addHeader("X-Aetheris-Identity",encoded+"."+java.util.HexFormat.of().formatHex(mac.doFinal(encoded.getBytes(StandardCharsets.UTF_8))));var response=new MockHttpServletResponse();assertFalse(new GatewayIdentityInterceptor(new ResourceAuthorization()).preHandle(request,response,new Object()));assertEquals(401,response.getStatus());}
 @Test void plainUsernameAttributeCannotEstablishSubject(){var request=new MockHttpServletRequest();request.setAttribute("aetheris.username","admin");RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));try{assertNull(new ResourceAuthorization().username());}finally{RequestContextHolder.resetRequestAttributes();}}
}
