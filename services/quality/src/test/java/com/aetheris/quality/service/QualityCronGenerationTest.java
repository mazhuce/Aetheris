package com.aetheris.quality.service;
import com.aetheris.quality.fixedexecution.*;
import com.aetheris.quality.security.V2QualityFixture;
import com.aetheris.security.servlet.TrustedUser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QualityCronGenerationTest {
    @Test void aCronActorCannotEnterTheManualClaimPathWithoutACurrentScheduleGeneration()throws Exception {
        try(var f=new V2QualityFixture()){
            var jdbc=mock(JdbcTemplate.class);var manager=mock(PlatformTransactionManager.class);
            var store=new QualityExecutionStore(jdbc,manager,mock(ExecutionRecoveryService.class),mock(QualityCheckResultService.class));
            f.request(r->assertThrows(IllegalStateException.class,()->store.claimManual(1,QualityActor.manual(TrustedUser.require(r)).forCron(),"forged-manual")));
            verifyNoInteractions(jdbc,manager);
        }
    }
}
