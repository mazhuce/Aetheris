package com.aetheris.workflow.service;

import com.aetheris.workflow.fixedexecution.FixedJson;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/** One submit only. Ambiguous submission is resolved by status, never by another execution POST. */
@Component
public class SqlExecutionClient {
    public static final String OP="metadata.fixed-sql.submit",SUBMIT="/internal/v2/executions/sql/submit",STATUS="/internal/v2/executions/status";
    private final SignedHttpClient transport;private final Duration waitTimeout,pollInterval;
    private final Clock clock;private final LongSupplier nanoTime;
    @Autowired public SqlExecutionClient(@Qualifier("workflowMetadataTransport") SignedHttpClient transport,
            @Value("${aetheris.fixed-execution.wait-timeout:PT1H}") Duration timeout) {this(transport,timeout,Duration.ofSeconds(1));}
    public SqlExecutionClient(SignedHttpClient transport,Duration timeout,Duration pollInterval) {
        this(transport,timeout,pollInterval,Clock.systemUTC(),System::nanoTime);
    }
    SqlExecutionClient(SignedHttpClient transport,Duration timeout,Duration pollInterval,Clock clock,LongSupplier nanoTime) {
        if(timeout==null||timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofHours(1))>0||pollInterval==null||pollInterval.isZero()||pollInterval.isNegative()||pollInterval.compareTo(Duration.ofSeconds(5))>0)throw new IllegalArgumentException("固定执行等待期限无效");
        this.transport=Objects.requireNonNull(transport);this.waitTimeout=timeout;this.pollInterval=pollInterval;
        this.clock=Objects.requireNonNull(clock);this.nanoTime=Objects.requireNonNull(nanoTime);
    }
    /** Retired raw SQL constructors and execution bridge cannot create any transport. */
    @Deprecated public SqlExecutionClient(org.springframework.web.client.RestClient.Builder ignored,String ignoredUrl,Duration ignoredTimeout,String ignoredToken){transport=null;waitTimeout=Duration.ZERO;pollInterval=Duration.ZERO;clock=Clock.systemUTC();nanoTime=System::nanoTime;}
    @Deprecated public SqlExecutionClient(org.springframework.web.client.RestClient.Builder ignored,String ignoredUrl,Duration ignoredTimeout){this(ignored,ignoredUrl,ignoredTimeout,"");}
    @Deprecated public ExecutionResult execute(Long datasourceId,String sql,int timeout){throw new IllegalStateException("SQL执行必须引用已批准封存attempt，禁止原始SQL桥");}
    public record Submission(long runId,long attemptId,long fence,long executionRevision,String manifestSha256) {
        public Submission {if(runId<=0||attemptId<=0||fence<=0||executionRevision<=0||manifestSha256==null||!manifestSha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("封存运行引用无效");}
    }
    /** Pure policy validation only; no business JDBC call exists on this route. */
    private static final String POLICY_REJECTED="SQL不符合固定写任务安全策略";
    public void requireWritePolicy(String sql){String reason=validateWrite(sql);if(reason!=null)throw new org.springframework.web.server.ResponseStatusException(POLICY_REJECTED.equals(reason)?org.springframework.http.HttpStatus.BAD_REQUEST:org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"SQL校验尚未通过，不能批准");}
    public String validateWrite(String sql){
        if(transport==null)return "SQL安全校验服务不可用，当前不能保存任务定义";
        try{
            var response=transport.send(new SignedHttpClient.Request("metadata","POST","/internal/v2/sql/validate-write","metadata.fixed-sql.validate-write",FixedJson.bytes(Map.of("sql",sql))));
            if(response.status()!=200)throw unknown();var n=FixedJson.parse(response.body(),1024);FixedJson.fields(n,"valid","reason");
            if(!n.get("valid").isBoolean())throw unknown();
            if(n.get("valid").booleanValue()){if(!n.get("reason").isNull())throw unknown();return null;}
            if(!"SQL_POLICY_REJECTED".equals(FixedJson.text(n,"reason")))throw unknown();return POLICY_REJECTED;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return "SQL安全校验已中断，不能保存任务定义";}catch(RuntimeException invalid){return "SQL安全校验服务不可用，当前不能保存任务定义";}
    }
    public ExecutionResult execute(Submission submission,LongConsumer bindReceipt) {
        return execute(submission,new Budget(nanoTime.getAsLong()+waitTimeout.toNanos(),null),bindReceipt);
    }
    /** The sealed source claim, not a global compatibility timeout, bounds production polling. */
    public ExecutionResult execute(Submission submission,OffsetDateTime sourceLeaseDeadline,LongConsumer bindReceipt) {
        try {
            Instant until=Objects.requireNonNull(sourceLeaseDeadline).toInstant().minusSeconds(5);
            long remaining=Duration.between(clock.instant(),until).toNanos();
            if(remaining<=0)throw unknown();
            return execute(submission,new Budget(nanoTime.getAsLong()+remaining,until),bindReceipt);
        }catch(SqlExecutionException safe){throw safe;}catch(RuntimeException invalid){throw unknown();}
    }
    private record Budget(long monotonicDeadline,Instant wallDeadline) { }
    private ExecutionResult execute(Submission submission,Budget deadline,LongConsumer bindReceipt) {
        if(transport==null)throw unknown();Long receipt=null;
        try {
            within(deadline);
            Map<String,Object> body=Map.of("runId",submission.runId(),"attemptId",submission.attemptId(),"fence",submission.fence(),"executionRevision",submission.executionRevision(),"manifestSha256",submission.manifestSha256());
            Receipt accepted=null;
            try {var response=transport.send(new SignedHttpClient.Request("metadata","POST",SUBMIT,OP,FixedJson.bytes(body)));
                if(response.status()!=202)throw unknown();within(deadline);accepted=read(response.body(),submission,null);
            }catch(SignedHttpClient.TransportException lost) {
                if(lost.failure()!=SignedHttpClient.Failure.IO && lost.failure()!=SignedHttpClient.Failure.DEADLINE)throw unknown();
            }
            if(accepted!=null){within(deadline);receipt=accepted.id();bindReceipt.accept(receipt);within(deadline);ExecutionResult done=terminal(accepted);if(done!=null)return done;}
            while(remaining(deadline)>0) {
                within(deadline);
                Map<String,Object> status=new LinkedHashMap<>();status.put("receiptId",receipt);status.put("runId",submission.runId());status.put("attemptId",submission.attemptId());status.put("fence",submission.fence());status.put("operation",OP);
                try {
                    var response=transport.send(new SignedHttpClient.Request("metadata","POST",STATUS,"metadata.fixed-execution.status",FixedJson.bytes(status)));
                    if(response.status()==200) {
                        within(deadline);Receipt current=read(response.body(),submission,receipt);
                        if(receipt==null){receipt=current.id();bindReceipt.accept(receipt);}
                        within(deadline);ExecutionResult done=terminal(current);if(done!=null)return done;
                    }else if(response.status()!=404)throw unknown();
                }catch(SignedHttpClient.TransportException unavailable) {
                    if(unavailable.failure()!=SignedHttpClient.Failure.IO && unavailable.failure()!=SignedHttpClient.Failure.DEADLINE)throw unknown();
                }
                long remaining=remaining(deadline);if(remaining<=0)break;
                java.util.concurrent.TimeUnit.NANOSECONDS.sleep(Math.min(pollInterval.toNanos(),remaining));
            }
            throw unknown();
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unknown();}
        catch(SqlExecutionException safe){throw safe;}
        catch(RuntimeException invalid){throw unknown();}
    }
    private record Receipt(long id,String state,ExecutionResult result) { }
    private Receipt read(byte[] raw,Submission source,Long expectedReceipt) {
        JsonNode n=FixedJson.parse(raw,16384);FixedJson.fields(n,"receiptId","origin","operation","runId","attemptId","fence","state","result","errorCode");
        long id=FixedJson.number(n,"receiptId",1);
        if(expectedReceipt!=null&&id!=expectedReceipt||!"workflow".equals(FixedJson.text(n,"origin"))||!OP.equals(FixedJson.text(n,"operation"))
                ||FixedJson.number(n,"runId",1)!=source.runId()||FixedJson.number(n,"attemptId",1)!=source.attemptId()||FixedJson.number(n,"fence",1)!=source.fence())throw unknown();
        String state=FixedJson.text(n,"state");JsonNode error=n.get("errorCode");
        if(!error.isNull()&&(!error.isTextual()||!error.textValue().matches("[A-Z][A-Z0-9_]{0,63}")))throw unknown();
        if(state.equals("succeeded")) {
            if(!error.isNull())throw unknown();var result=n.get("result");FixedJson.fields(result,"affectedRows","durationMs");
            return new Receipt(id,state,new ExecutionResult(result.get("affectedRows").isNull()?null:FixedJson.number(result,"affectedRows",0),FixedJson.number(result,"durationMs",0)));
        }
        if(!Set.of("queued","executing","unknown","failed").contains(state)||!n.get("result").isNull()||state.equals("failed")&&error.isNull())throw unknown();
        return new Receipt(id,state,null);
    }
    private ExecutionResult terminal(Receipt receipt) {
        return switch(receipt.state()) {case "succeeded"->receipt.result();case "failed"->throw new SqlExecutionException("元数据执行已返回失败回执",false,false);case "unknown"->throw unknown();default->null;};
    }
    private long remaining(Budget deadline){
        long remaining=deadline.monotonicDeadline()-nanoTime.getAsLong();
        if(deadline.wallDeadline()!=null)remaining=Math.min(remaining,Duration.between(clock.instant(),deadline.wallDeadline()).toNanos());
        return remaining;
    }
    private void within(Budget deadline){if(Thread.currentThread().isInterrupted()||remaining(deadline)<=0)throw unknown();}
    private static SqlExecutionException unknown(){return new SqlExecutionException("执行结果尚未可靠确认，禁止自动重放",true,false);}
    public record ExecutionResult(Long affectedRows,long durationMs) {
        public ExecutionResult {if(affectedRows!=null&&affectedRows<0||durationMs<0)throw new IllegalArgumentException("执行计数无效");}
    }
}
