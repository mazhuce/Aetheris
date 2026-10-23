package com.aetheris.system.controller;
import com.aetheris.system.dto.PermissionEventRequest;
import com.aetheris.system.entity.PermissionEvent;
import com.aetheris.system.security.*;
import com.aetheris.system.service.PermissionEventService;
import jakarta.servlet.http.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class InternalPermissionEventControllerTest {
 @Test void persistsDeniedPermissionEvent() throws Exception {
  var event=new PermissionEventRequest("alice",42L,"metric","5","query","DENIED","No permission","req-1");var saved=save(event);
  assertEquals("alice",saved.getUsername());assertEquals(42L,saved.getUserId());assertEquals("DENIED",saved.getDecision());assertEquals("req-1",saved.getRequestId());
 }
 @Test void persistsGrantedEventWithoutOptionalFields() throws Exception {var saved=save(new PermissionEventRequest(null,null,"role","9","create","GRANTED",null,null));assertNull(saved.getUsername());assertEquals("GRANTED",saved.getDecision());}
 private PermissionEvent save(PermissionEventRequest event) throws Exception {
  var service=mock(PermissionEventService.class);
  try(var factory=Validation.buildDefaultValidatorFactory()) {
   var controller=new InternalPermissionEventController(service,new SystemInternalRequests(factory.getValidator()));
   var response=V2TestFixture.invoke("auth","/internal/v2/audit/permission-events","system.permission-event.record",event,(req,res)->((HttpServletResponse)res).setStatus(controller.create((HttpServletRequest)req).getStatusCode().value()));assertEquals(202,response.getStatus());
   var captor=ArgumentCaptor.forClass(PermissionEvent.class);verify(service).save(captor.capture());return captor.getValue();
  }
 }
}
