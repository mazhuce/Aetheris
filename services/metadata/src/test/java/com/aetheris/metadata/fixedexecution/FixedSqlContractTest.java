package com.aetheris.metadata.fixedexecution;
import com.aetheris.security.protocol.BodyHash;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.aetheris.metadata.fixedexecution.FixedJson.*;
class FixedSqlContractTest {
    @Test void operationsNeverBorrowEachOthersAttemptRules(){
        var sql=new FixedExecutionAdapter.Submission(11,22,3,2,"a".repeat(64));FixedExecutionOperation.SQL.validate(sql);
        for(var op:List.of(FixedExecutionOperation.TRANSFER,FixedExecutionOperation.QUALITY))assertThrows(IllegalArgumentException.class,()->op.validate(sql));
        var other=new FixedExecutionAdapter.Submission(11,0,1,2,"a".repeat(64));assertThrows(IllegalArgumentException.class,()->FixedExecutionOperation.SQL.validate(other));
        var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);var store=new FixedReceiptStore(jdbc);
        for(var op:List.of(FixedExecutionOperation.TRANSFER,FixedExecutionOperation.QUALITY))assertThrows(IllegalArgumentException.class,()->store.find(op,11,22,3));
        assertThrows(IllegalArgumentException.class,()->store.find(FixedExecutionOperation.SQL,11,0,1));verifyNoInteractions(jdbc);
    }
    @Test void compilesOnlyFourteenFieldManifestAndSeventeenFieldDefinition() throws Exception {
        try(var p=new FixedSqlTestPeers()){
            var m=p.install(11,8,"INSERT INTO business.sql_output SELECT '${bizDate}'",1800,s->s);assertEquals("INSERT INTO business.sql_output SELECT '2026-10-18'",m.definition().sql());
            assertEquals(17,parse(m.canonical()).get("execution").size());assertEquals(14,parse(m.canonical()).size());assertEquals(1800,m.definition().timeoutSeconds());
            for(String mutation:List.of("actor","parent","parameter","bad-date","wrong-origin","wrong-type","hash","extra","unknown-template","select","update","delete","decimal","nested")){
                var n=(ObjectNode)parse(m.canonical());var d=(ObjectNode)n.get("execution");
                switch(mutation){case "actor"->((ObjectNode)n.get("actor")).put("kind","workflow-child");case "parent"->n.putObject("parent");case "parameter"->((ObjectNode)n.get("parameters")).put("sql","SELECT 1");case "bad-date"->((ObjectNode)n.get("parameters")).put("bizDate","2026-02-30");case "wrong-origin"->n.put("origin","ingestion");case "wrong-type"->d.put("taskType","spark");case "hash"->n.put("definitionSha256","b".repeat(64));case "extra"->d.put("url","jdbc:invalid");case "unknown-template"->d.put("sql","INSERT INTO t SELECT '${biz_date}'");case "select"->d.put("sql","SELECT 1");case "update"->d.put("sql","UPDATE t SET x=1");case "delete"->d.put("sql","DELETE FROM t");case "decimal"->d.put("timeoutSeconds",1.5);case "nested"->d.putObject("dependencies").putArray("upstreamTaskIds").add("admin");}
                if(!mutation.equals("extra")&&!mutation.equals("decimal")&&!mutation.equals("nested")){String dh=FixedSqlJsonb.hash(d);n.put("definitionSha256",dh);((ObjectNode)n.get("approval")).put("definitionSha256",dh);if(mutation.equals("hash"))n.put("definitionSha256","b".repeat(64));}
                String raw=string(n),hash=BodyHash.sha256(raw.getBytes(StandardCharsets.UTF_8));var input=new FixedExecutionAdapter.Submission(11,111,3,2,hash);
                assertThrows(IllegalArgumentException.class,()->FixedSqlManifest.parse(raw,hash,input),mutation);
            }
        }
    }
    @Test void pipelineManifestRetainsItsOriginalKindForAuthorization() throws Exception {
        try(var peers=new FixedSqlTestPeers()){
            var original=peers.install(11,8,"INSERT INTO business.sql_output SELECT 1",1800,s->s);
            var n=(ObjectNode)parse(original.canonical());((ObjectNode)n.get("actor")).put("kind","pipeline");
            String raw=string(n),hash=BodyHash.sha256(raw.getBytes(StandardCharsets.UTF_8));
            var manifest=FixedSqlManifest.parse(raw,hash,new FixedExecutionAdapter.Submission(11,111,3,2,hash));
            assertEquals("pipeline",((FixedTransferManifest.Actor)manifest.authorizationRequest().get("actor")).kind());
            assertNull(manifest.authorizationRequest().get("parent"));
        }
    }
    @Test void sqlResultAcceptsOnlyExplicitSuccessAndStrictIntegerCounts(){
        assertEquals(new FixedSqlResult(null,1),FixedSqlResult.fromJdbc(Map.of("success",true,"affectedRows",-1,"durationMs",1)));
        assertEquals(new FixedSqlResult(0L,1),FixedSqlResult.fromJdbc(Map.of("success",true,"affectedRows",0L,"durationMs",1)));
        for(Object rows:List.of(-2,1.0,"1",new java.math.BigInteger("9223372036854775808")))assertThrows(IllegalArgumentException.class,()->FixedSqlResult.fromJdbc(Map.of("success",true,"affectedRows",rows,"durationMs",1)));
        assertThrows(IllegalArgumentException.class,()->FixedSqlResult.fromJdbc(Map.of("success",false,"affectedRows",1,"durationMs",1)));
        assertThrows(IllegalArgumentException.class,()->FixedSqlResult.fromJdbc(Map.of("affectedRows",1,"durationMs",1)));
        assertThrows(IllegalArgumentException.class,()->FixedSqlResult.fromJdbc(Map.of("success",true,"affectedRows",1,"durationMs",-1)));
        assertTrue(string(new FixedSqlResult(null,1)).contains("\"affectedRows\":null"));
    }
}
