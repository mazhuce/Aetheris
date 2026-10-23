package com.aetheris.metadata.fixedexecution;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import static com.aetheris.metadata.fixedexecution.FixedJson.*;

/** Durable queue and irreversible dispatch fence. No HTTP/JDBC business call runs in a store transaction. */
@Repository
public class FixedReceiptStore {
    private final JdbcTemplate jdbc;
    public FixedReceiptStore(JdbcTemplate jdbc){this.jdbc=Objects.requireNonNull(jdbc);}
    public record Row(long id,long runId,long attemptId,long fence,long revision,String hash,String manifest,
                      String execution,String state,String worker,long workerFence,OffsetDateTime lease,
                      OffsetDateTime deadline,String result,String error,FixedExecutionOperation operation) {
        public Row{require(operation!=null);operation.validate(runId,attemptId,fence);}
        public Row(long id,long runId,long attemptId,long fence,long revision,String hash,String manifest,String execution,String state,String worker,long workerFence,OffsetDateTime lease,OffsetDateTime deadline,String result,String error){this(id,runId,attemptId,fence,revision,hash,manifest,execution,state,worker,workerFence,lease,deadline,result,error,FixedExecutionOperation.TRANSFER);}
        public FixedExecutionAdapter.Submission submission(){var input=new FixedExecutionAdapter.Submission(runId,attemptId,fence,revision,hash);operation.validate(input);return input;}
        public FixedTransferManifest.Submit submit(){return new FixedTransferManifest.Submit(runId,attemptId,fence,revision,hash);}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Result(long rowsRead,long rowsWritten,long durationMs) implements FixedExecutionAdapter.ExecutionResult {
        public Result{require(rowsRead>=0&&rowsWritten>=0&&durationMs>=0);}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Receipt(long receiptId,String origin,String operation,long runId,long attemptId,long fence,
                          String state,FixedExecutionAdapter.ExecutionResult result,String errorCode) { }
    private static final String SELECT="SELECT origin,operation,id,run_id,attempt_id,source_fence,execution_revision,manifest_sha256,source_manifest_json::text manifest,execution_json::text execution,status,worker_id,worker_fence,worker_lease_expires_at,deadline_at,result_json::text result,safe_error FROM metadata.execution_receipt ";
    private static final String OWN="((origin='ingestion' AND operation='metadata.fixed-transfer.submit' AND attempt_id=0 AND source_fence=1) OR (origin='quality' AND operation='metadata.fixed-quality-query.submit' AND attempt_id=0 AND source_fence=1) OR (origin='workflow' AND operation='metadata.fixed-sql.submit' AND attempt_id>0 AND source_fence>0))";
    private Row row(ResultSet r,int index)throws SQLException{return new Row(r.getLong("id"),r.getLong("run_id"),r.getLong("attempt_id"),r.getLong("source_fence"),r.getLong("execution_revision"),r.getString("manifest_sha256"),r.getString("manifest"),r.getString("execution"),r.getString("status"),r.getString("worker_id"),r.getLong("worker_fence"),r.getObject("worker_lease_expires_at",OffsetDateTime.class),r.getObject("deadline_at",OffsetDateTime.class),r.getString("result"),r.getString("safe_error"),FixedExecutionOperation.stored(r.getString("origin"),r.getString("operation")));}
    public Optional<Row> find(long runId,long attemptId,long fence){return find(FixedExecutionOperation.TRANSFER,runId,attemptId,fence);}
    public Optional<Row> find(FixedExecutionOperation operation,long runId,long attemptId,long fence){operation.validate(runId,attemptId,fence);return jdbc.query(SELECT+"WHERE origin=? AND operation=? AND run_id=? AND attempt_id=? AND source_fence=?",this::row,operation.origin(),operation.operation(),runId,attemptId,fence).stream().findFirst();}
    public Row requireCompatible(Row row,FixedTransferManifest.Submit input){return requireCompatible(row,FixedExecutionOperation.TRANSFER,new FixedExecutionAdapter.Submission(input.runId(),input.attemptId(),input.fence(),input.executionRevision(),input.manifestSha256()));}
    public Row requireCompatible(Row row,FixedExecutionOperation operation,FixedExecutionAdapter.Submission input){
        operation.validate(input);row.operation().validate(row.runId(),row.attemptId(),row.fence());
        if(row.operation()!=operation||row.runId()!=input.runId()||row.attemptId()!=input.attemptId()||row.fence()!=input.fence()||row.revision()!=input.executionRevision()||!row.hash().equals(input.manifestSha256()))throw new Conflict();return row;
    }
    public Row insert(FixedTransferManifest manifest,String execution,String rawHash,Duration lifetime){
        var b=manifest.binding();return insert(new FixedExecutionAdapter.Invocation(FixedExecutionOperation.TRANSFER,new FixedExecutionAdapter.Submission(b.runId(),b.attemptId(),b.fence(),b.executionRevision(),b.manifestSha256()),manifest.canonical()),execution,rawHash,lifetime);
    }
    public Row insert(FixedExecutionAdapter.Invocation manifest,String execution,String rawHash,Duration lifetime){
        var b=manifest.submission();var operation=manifest.operation();operation.validate(b);
        if(operation==FixedExecutionOperation.SQL){var sql=FixedSqlManifest.parse(manifest.canonical(),b.manifestSha256(),b);lifetime=Duration.ofSeconds(sql.definition().timeoutSeconds()+120L);}
        require(lifetime!=null&&!lifetime.isNegative()&&!lifetime.isZero()&&(operation!=FixedExecutionOperation.SQL||lifetime.getSeconds()<=86520));
        jdbc.update("""
            INSERT INTO metadata.execution_receipt(origin,operation,run_id,attempt_id,source_fence,execution_revision,
                manifest_sha256,raw_request_sha256,source_manifest_json,execution_json,deadline_at)
            VALUES(?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,clock_timestamp()+(? * INTERVAL '1 millisecond'))
            ON CONFLICT(origin,operation,run_id,attempt_id,source_fence) DO NOTHING
            """,operation.origin(),operation.operation(),b.runId(),b.attemptId(),b.fence(),b.executionRevision(),b.manifestSha256(),rawHash,manifest.canonical(),execution,lifetime.toMillis());
        return requireCompatible(find(operation,b.runId(),b.attemptId(),b.fence()).orElseThrow(Unavailable::new),operation,b);
    }
    public Receipt receipt(Row row){
        row.operation().validate(row.submission());
        FixedExecutionAdapter.ExecutionResult result=null;
        if("succeeded".equals(row.state())){result=row.operation().result(row.result());require(row.error()==null);}
        else require(row.result()==null);
        require(Set.of("queued","executing","succeeded","failed","unknown").contains(row.state()));
        require(row.error()==null||row.error().matches("[A-Z][A-Z0-9_]{0,63}"));
        require(!"failed".equals(row.state())||row.error()!=null);
        return new Receipt(row.id(),row.operation().origin(),row.operation().operation(),row.runId(),row.attemptId(),row.fence(),row.state(),result,row.error());
    }
    public List<Long> queued(int limit){return jdbc.queryForList("SELECT id FROM metadata.execution_receipt WHERE "+OWN+" AND status='queued' AND deadline_at>clock_timestamp() ORDER BY id LIMIT ?",Long.class,limit);}
    public Optional<Row> claim(long id,String owner){
        int changed=jdbc.update("""
            UPDATE metadata.execution_receipt SET status='executing',worker_id=?,worker_fence=worker_fence+1,
              started_at=clock_timestamp(),worker_lease_expires_at=least(deadline_at,clock_timestamp()+INTERVAL '120 seconds')
            WHERE id=? AND ((origin='ingestion' AND operation='metadata.fixed-transfer.submit' AND attempt_id=0 AND source_fence=1) OR (origin='quality' AND operation='metadata.fixed-quality-query.submit' AND attempt_id=0 AND source_fence=1) OR (origin='workflow' AND operation='metadata.fixed-sql.submit' AND attempt_id>0 AND source_fence>0)) AND status='queued' AND deadline_at>clock_timestamp()
            """,owner,id);
        return changed==1?jdbc.query(SELECT+"WHERE id=?",this::row,id).stream().findFirst():Optional.empty();
    }
    public boolean live(Row row){row.operation().validate(row.submission());return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM metadata.execution_receipt WHERE id=? AND status='executing' AND worker_id=? AND worker_fence=? AND worker_lease_expires_at>clock_timestamp() AND deadline_at>clock_timestamp())",Boolean.class,row.id(),row.worker(),row.workerFence()));}
    public boolean renew(Row row){row.operation().validate(row.submission());return jdbc.update("UPDATE metadata.execution_receipt SET worker_lease_expires_at=least(deadline_at,clock_timestamp()+INTERVAL '120 seconds') WHERE id=? AND status='executing' AND worker_id=? AND worker_fence=? AND worker_lease_expires_at>clock_timestamp() AND deadline_at>clock_timestamp()",row.id(),row.worker(),row.workerFence())==1;}
    public boolean finish(Row row,String state,FixedExecutionAdapter.ExecutionResult result,String code){
        row.operation().validate(row.submission());
        require(Set.of("succeeded","failed","unknown").contains(state));require(("succeeded".equals(state)&&result!=null&&code==null)||(!"succeeded".equals(state)&&result==null&&code!=null&&code.matches("[A-Z][A-Z0-9_]{0,63}")));
        if(result!=null)row.operation().result(string(result));
        return jdbc.update("""
            UPDATE metadata.execution_receipt SET status=?,result_json=?::jsonb,safe_error=?,finished_at=clock_timestamp()
            WHERE id=? AND status='executing' AND worker_id=? AND worker_fence=? AND worker_lease_expires_at>clock_timestamp() AND deadline_at>clock_timestamp()
            """,state,result==null?null:string(result),code,row.id(),row.worker(),row.workerFence())==1;
    }
    /** Expiry is never a new dispatch authorization; other replicas' live workers are untouched. */
    public void expire(){
        jdbc.update("UPDATE metadata.execution_receipt SET status='failed',safe_error='QUEUE_EXPIRED',finished_at=clock_timestamp() WHERE "+OWN+" AND status='queued' AND deadline_at<=clock_timestamp()");
        jdbc.update("UPDATE metadata.execution_receipt SET status='unknown',result_json=NULL,safe_error='WORKER_LEASE_EXPIRED',finished_at=clock_timestamp() WHERE "+OWN+" AND status='executing' AND (worker_lease_expires_at<=clock_timestamp() OR deadline_at<=clock_timestamp())");
    }
    public static final class Conflict extends RuntimeException {public Conflict(){super("Execution receipt binding conflict");}}
    public static final class Unavailable extends RuntimeException {public Unavailable(){super("Execution receipt unavailable");}}
}
