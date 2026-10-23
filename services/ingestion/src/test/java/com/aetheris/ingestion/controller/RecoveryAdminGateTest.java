package com.aetheris.ingestion.controller;

import com.aetheris.ingestion.security.V2IngestionFixture;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryAdminGateTest {
    @Test void onlyExplicitSignedPositiveDecisionPermitsReconciliation() throws Exception {
        try(var f=new V2IngestionFixture()) {
            var gate=new RecoveryAdminGate(f.authorization);
            f.request(r->assertEquals(403,assertThrows(ResponseStatusException.class,()->gate.requireManage("alice")).getStatusCode().value()));
            f.manage.set(true);f.request(r->assertDoesNotThrow(()->gate.requireManage("alice")));
            f.request(r->assertEquals(403,assertThrows(ResponseStatusException.class,()->gate.requireManage("admin")).getStatusCode().value()));
            for(String fault:java.util.List.of("unsigned","tampered","signed500")) {
                f.fault=fault;f.permissionFaultOnly=true;
                f.request(r->assertEquals(503,assertThrows(ResponseStatusException.class,()->gate.requireManage("alice")).getStatusCode().value()));
            }
            assertEquals(403,assertThrows(ResponseStatusException.class,()->gate.requireManage("alice")).getStatusCode().value());
        }
    }
}
