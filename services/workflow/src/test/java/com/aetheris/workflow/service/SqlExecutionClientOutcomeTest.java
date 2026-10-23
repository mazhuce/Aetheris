package com.aetheris.workflow.service;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SqlExecutionClientOutcomeTest {
    @Test void unknownAlwaysDisablesAutomaticRetry(){assertFalse(new SqlExecutionException("unknown",true,true).retryable());}
    @Test void strictResultPreservesNullInsteadOfFabricatingZero(){assertNull(new SqlExecutionClient.ExecutionResult(null,1).affectedRows());assertThrows(IllegalArgumentException.class,()->new SqlExecutionClient.ExecutionResult(-1L,1));}
    @Test void receiptSourceTupleCannotBeZeroOrMalformed(){
        assertThrows(IllegalArgumentException.class,()->new SqlExecutionClient.Submission(1,0,1,1,"a".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->new SqlExecutionClient.Submission(1,2,0,1,"a".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->new SqlExecutionClient.Submission(1,2,1,1,"old"));
    }
}
