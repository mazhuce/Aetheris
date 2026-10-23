package com.aetheris.quality.controller;
import com.aetheris.quality.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
class RecoveryAdminGateTest {
 @Test void onlyCurrentVerifiedUserAndExplicitManageDecisionCanReconcile() throws Exception {
  try(var f=new V2QualityFixture()) {
   var gate=new RecoveryAdminGate(f.authorization);
   assertEquals(401,assertThrows(ResponseStatusException.class,()->gate.requireManage("alice")).getStatusCode().value());
   f.request(r->{assertEquals(403,assertThrows(ResponseStatusException.class,()->gate.requireManage("alice")).getStatusCode().value());});
   f.manage.set(true);f.request(r->{assertDoesNotThrow(()->gate.requireManage("alice"));
    assertEquals(403,assertThrows(ResponseStatusException.class,()->gate.requireManage("admin")).getStatusCode().value());
    assertEquals(403,assertThrows(ResponseStatusException.class,()->gate.requireManage("Alice")).getStatusCode().value());});
   f.fault="unsigned";f.permissionFaultOnly=true;
   f.request(r->assertEquals(503,assertThrows(ResponseStatusException.class,()->gate.requireManage("alice")).getStatusCode().value()));
  }
 }
}
