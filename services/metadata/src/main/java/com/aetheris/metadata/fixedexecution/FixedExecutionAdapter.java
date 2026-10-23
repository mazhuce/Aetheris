package com.aetheris.metadata.fixedexecution;

import java.nio.charset.StandardCharsets;
import com.aetheris.security.protocol.BodyHash;
import static com.aetheris.metadata.fixedexecution.FixedJson.*;

/** All cross-thread state is primitive or canonical text; no request, proof, JsonNode, or mutable datasource. */
public interface FixedExecutionAdapter {
    record Submission(long runId,long attemptId,long fence,long executionRevision,String manifestSha256){
        public Submission{require(runId>0&&((attemptId==0&&fence==1)||(attemptId>0&&fence>0))&&executionRevision>0&&manifestSha256!=null&&manifestSha256.matches("[a-f0-9]{64}"));}
        public static Submission parse(byte[] raw){require(raw!=null&&raw.length<=65536);var n=FixedJson.parse(raw);fields(n,"runId","attemptId","fence","executionRevision","manifestSha256");return new Submission(number(n,"runId"),number(n,"attemptId"),number(n,"fence"),number(n,"executionRevision"),hash(n,"manifestSha256"));}
        public static Submission parse(FixedExecutionOperation operation,byte[] raw){var result=parse(raw);operation.validate(result);return result;}
        public FixedTransferManifest.Submit transfer(){return new FixedTransferManifest.Submit(runId,attemptId,fence,executionRevision,manifestSha256);}
    }
    record SourceLease(java.time.Instant expiresAt){public SourceLease{require(expiresAt!=null);}}
    record DispatchBudget(java.time.Instant deadline){public DispatchBudget{require(deadline!=null);}}
    record Invocation(FixedExecutionOperation operation,Submission submission,String canonical,SourceLease sourceLease){
        public Invocation(FixedExecutionOperation operation,Submission submission,String canonical){this(operation,submission,canonical,null);}
        public Invocation{require(operation!=null&&submission!=null&&canonical!=null&&canonical.getBytes(StandardCharsets.UTF_8).length<=768*1024&&BodyHash.sha256(canonical.getBytes(StandardCharsets.UTF_8)).equals(submission.manifestSha256()));operation.validate(submission);require((operation==FixedExecutionOperation.SQL)==(sourceLease!=null));}
    }
    record Prepared(Invocation invocation,String normalized){
        public Prepared{require(invocation!=null&&normalized!=null&&FixedJson.parse(normalized).isObject());}
    }
    sealed interface ExecutionResult permits FixedReceiptStore.Result,FixedQualityResult,FixedSqlResult { }
    FixedExecutionOperation operation();
    Invocation resolveAndAuthorize(Submission submission);
    Prepared prepare(Invocation invocation);
    Prepared verify(Invocation invocation,String stored);
    ExecutionResult execute(Prepared prepared);
    default ExecutionResult execute(Prepared prepared,DispatchBudget budget){require(operation()!=FixedExecutionOperation.SQL&&budget==null);return execute(prepared);}
}
