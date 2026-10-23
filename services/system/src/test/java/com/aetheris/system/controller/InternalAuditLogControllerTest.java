package com.aetheris.system.controller;
import com.aetheris.system.dto.AuditLogRequest;
import com.aetheris.system.entity.AuditLog;
import com.aetheris.system.security.*;
import com.aetheris.system.service.AuditLogService;
import jakarta.servlet.http.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class InternalAuditLogControllerTest {
 @Test void persistsGatewayAuditEvent() throws Exception {
  var service=mock(AuditLogService.class);
  try(var factory=Validation.buildDefaultValidatorFactory()) {
   var controller=new InternalAuditLogController(service,new SystemInternalRequests(factory.getValidator()));
   var response=V2TestFixture.invoke("gateway","/internal/v2/audit/logs","system.audit.record",new AuditLogRequest("admin","admin","POST","/api/quality/rules",201,48L,"127.0.0.1"),(req,res)->((HttpServletResponse)res).setStatus(controller.create((HttpServletRequest)req).getStatusCode().value()));
   var captor=ArgumentCaptor.forClass(AuditLog.class);verify(service).save(captor.capture());assertEquals(202,response.getStatus());assertEquals("admin",captor.getValue().getUsername());assertEquals("/api/quality/rules",captor.getValue().getPath());assertEquals(201,captor.getValue().getStatusCode());
  }
 }
}
