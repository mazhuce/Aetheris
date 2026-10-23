package com.aetheris.ingestion.service;
import com.aetheris.ingestion.fixedexecution.*;
import com.aetheris.ingestion.security.V2IngestionFixture;
import com.aetheris.security.servlet.TrustedUser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IngestionCronGenerationTest {
    @Test void aCronActorCannotEnterTheManualClaimPathWithoutACurrentScheduleGeneration()throws Exception {
        try(var f=new V2IngestionFixture()){
            var jdbc=mock(JdbcTemplate.class);var manager=mock(PlatformTransactionManager.class);
            var store=new IngestionExecutionStore(jdbc,manager,mock(ExecutionRecoveryService.class),mock(IngestionRunService.class));
            f.request(r->assertThrows(IllegalStateException.class,()->store.claimManual(1,IngestionActor.manual(TrustedUser.require(r)).forCron(),"forged-manual",null)));
            verifyNoInteractions(jdbc,manager);
        }
    }
}
