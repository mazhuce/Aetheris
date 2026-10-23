package com.aetheris.metadata.fixedexecution;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.OffsetDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class FixedSqlBoundaryTest {
    static final String HASH="a".repeat(64);
    @Test void positiveAttemptReferenceMustReachServerOperationValidation(){assertDoesNotThrow(()->new FixedExecutionAdapter.Submission(11,22,3,2,HASH));}
    @Test void sameHashAndRevisionCannotSubstituteAnotherRunTuple(){
        var jdbc=mock(JdbcTemplate.class);var store=new FixedReceiptStore(jdbc);
        var row=new FixedReceiptStore.Row(99,12,0,1,2,HASH,"{}","{}","queued",null,0,null,OffsetDateTime.now().plusMinutes(1),null,null);
        assertThrows(FixedReceiptStore.Conflict.class,()->store.requireCompatible(row,FixedExecutionOperation.TRANSFER,new FixedExecutionAdapter.Submission(11,0,1,2,HASH)));verifyNoInteractions(jdbc);
    }
}
