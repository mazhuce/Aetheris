package com.aetheris.metadata.fixedexecution;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class FixedSqlPeersTest {
    @Test void signedSourceAndThreeExactAuthChecksCarryLocalLeaseOnly() throws Exception {
        try(var p=new FixedSqlTestPeers()){
            var m=p.install(11,8,"INSERT INTO business.sql_output SELECT 1",86400,s->s);var resolved=p.peers.resolveAndAuthorize(p.input(m));assertEquals(m.canonical(),resolved.manifest().canonical());assertTrue(resolved.lease().expiresAt().isAfter(p.keys.clock.instant().plusSeconds(86000)));assertFalse(FixedJson.string(m.authorizationRequest()).contains("lease"));assertEquals(1,p.lookups.get());assertEquals(1,p.checks.get());
        }
    }
    @Test void sourceAuthTamperWrongChecksInactiveAndExpiredLeaseFailClosed() throws Exception {
        for(String fault:java.util.List.of("unsigned","tampered","wrong-binding","missing-check","inactive"))try(var p=new FixedSqlTestPeers()){
            var m=p.install(11,8,"INSERT INTO business.sql_output SELECT 1",1800,s->s);p.fault=fault;
            assertThrows(RuntimeException.class,()->p.peers.resolveAndAuthorize(p.input(m)),fault);
            if(fault.equals("unsigned")||fault.equals("tampered")){p.faultOnSource=true;assertThrows(RuntimeException.class,()->p.peers.resolveAndAuthorize(p.input(m)));}
        }
        try(var p=new FixedSqlTestPeers()){
            var m=p.install(11,8,"INSERT INTO business.sql_output SELECT 1",1800,s->s);p.allowed.set(false);assertThrows(FixedTransferPeers.Rejected.class,()->p.peers.resolveAndAuthorize(p.input(m)));p.allowed.set(true);
            p.sourceActive.set(false);assertThrows(FixedTransferPeers.Rejected.class,()->p.peers.resolveAndAuthorize(p.input(m)));p.sourceActive.set(true);
            p.leaseOverride=p.keys.clock.instant().getEpochSecond()+4;assertThrows(FixedTransferPeers.Rejected.class,()->p.peers.resolveAndAuthorize(p.input(m)));
            p.leaseOverride=p.keys.clock.instant().getEpochSecond()+90000;assertThrows(FixedTransferPeers.Rejected.class,()->p.peers.resolveAndAuthorize(p.input(m)));
        }
    }
}
