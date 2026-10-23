package com.aetheris.ingestion.service;

import com.aetheris.ingestion.entity.IngestionTask;
import com.aetheris.ingestion.fixedexecution.FixedJson;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;
import java.util.function.LongConsumer;

/** One submit only. Ambiguous submission is resolved by status, never by another execution POST. */
@Component
public class MetadataIngestionClient {
    public static final String OP="metadata.fixed-transfer.submit",SUBMIT="/internal/v2/executions/transfer/submit",STATUS="/internal/v2/executions/status";
    private final SignedHttpClient transport;private final Duration waitTimeout,pollInterval;
    @Autowired public MetadataIngestionClient(@Qualifier("ingestionMetadataTransport") SignedHttpClient transport,
            @Value("${aetheris.fixed-execution.wait-timeout:PT1H}") Duration timeout) {this(transport,timeout,Duration.ofSeconds(1));}
    public MetadataIngestionClient(SignedHttpClient transport,Duration timeout,Duration pollInterval) {
        if(timeout==null||timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofHours(1))>0||pollInterval==null||pollInterval.isZero()||pollInterval.isNegative()||pollInterval.compareTo(Duration.ofSeconds(5))>0)throw new IllegalArgumentException("固定执行等待期限无效");
        this.transport=Objects.requireNonNull(transport);this.waitTimeout=timeout;this.pollInterval=pollInterval;
    }
    /** Retired test/source compatibility bridge; it cannot dispatch a legacy request. */
    @Deprecated MetadataIngestionClient(org.springframework.web.client.RestClient ignored){transport=null;waitTimeout=Duration.ZERO;pollInterval=Duration.ZERO;}
    @Deprecated public TransferResult execute(IngestionTask ignored){throw new IllegalStateException("固定执行必须引用封存运行，禁止直接提交任务SQL");}
    public record Submission(long runId,long executionRevision,String manifestSha256) {
        public Submission {if(runId<=0||executionRevision<=0||manifestSha256==null||!manifestSha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("封存运行引用无效");}
    }
    public TransferResult execute(Submission submission,LongConsumer bindReceipt) {
        if(transport==null)throw unknown();long deadline=System.nanoTime()+waitTimeout.toNanos();Long receipt=null;
        try {
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            Map<String,Object> body=Map.of("runId",submission.runId(),"attemptId",0,"fence",1,"executionRevision",submission.executionRevision(),"manifestSha256",submission.manifestSha256());
            Receipt accepted=null;
            try {var response=transport.send(new SignedHttpClient.Request("metadata","POST",SUBMIT,OP,FixedJson.bytes(body)));
                if(response.status()!=202)throw unknown();within(deadline);accepted=read(response.body(),submission,null);
            }catch(SignedHttpClient.TransportException lost) {
                if(lost.failure()!=SignedHttpClient.Failure.IO && lost.failure()!=SignedHttpClient.Failure.DEADLINE)throw unknown();
            }
            if(accepted!=null){within(deadline);receipt=accepted.id();bindReceipt.accept(receipt);within(deadline);TransferResult done=terminal(accepted);if(done!=null)return done;}
            while(System.nanoTime()-deadline<0) {
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                Map<String,Object> status=new LinkedHashMap<>();status.put("receiptId",receipt);status.put("runId",submission.runId());status.put("attemptId",0);status.put("fence",1);status.put("operation",OP);
                try {
                    var response=transport.send(new SignedHttpClient.Request("metadata","POST",STATUS,"metadata.fixed-execution.status",FixedJson.bytes(status)));
                    if(response.status()==200) {
                        within(deadline);Receipt current=read(response.body(),submission,receipt);
                        if(receipt==null){receipt=current.id();bindReceipt.accept(receipt);}
                        within(deadline);TransferResult done=terminal(current);if(done!=null)return done;
                    }else if(response.status()!=404)throw unknown();
                }catch(SignedHttpClient.TransportException unavailable) {
                    if(unavailable.failure()!=SignedHttpClient.Failure.IO && unavailable.failure()!=SignedHttpClient.Failure.DEADLINE)throw unknown();
                }
                long remaining=deadline-System.nanoTime();if(remaining<=0)break;
                java.util.concurrent.TimeUnit.NANOSECONDS.sleep(Math.min(pollInterval.toNanos(),remaining));
            }
            throw unknown();
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unknown();}
        catch(IngestionExecutionException safe){throw safe;}
        catch(RuntimeException invalid){throw unknown();}
    }
    private record Receipt(long id,String state,TransferResult result) { }
    private Receipt read(byte[] raw,Submission source,Long expectedReceipt) {
        JsonNode n=FixedJson.parse(raw,16384);FixedJson.fields(n,"receiptId","origin","operation","runId","attemptId","fence","state","result","errorCode");
        long id=FixedJson.number(n,"receiptId",1);
        if(expectedReceipt!=null&&id!=expectedReceipt||!"ingestion".equals(FixedJson.text(n,"origin"))||!OP.equals(FixedJson.text(n,"operation"))
                ||FixedJson.number(n,"runId",1)!=source.runId()||FixedJson.number(n,"attemptId",0)!=0||FixedJson.number(n,"fence",1)!=1)throw unknown();
        String state=FixedJson.text(n,"state");JsonNode error=n.get("errorCode");
        if(!error.isNull()&&(!error.isTextual()||!error.textValue().matches("[A-Z][A-Z0-9_]{0,63}")))throw unknown();
        if(state.equals("succeeded")) {
            if(!error.isNull())throw unknown();var result=n.get("result");FixedJson.fields(result,"rowsRead","rowsWritten","durationMs");
            return new Receipt(id,state,new TransferResult(FixedJson.number(result,"rowsRead",0),FixedJson.number(result,"rowsWritten",0),FixedJson.number(result,"durationMs",0)));
        }
        if(!Set.of("queued","executing","unknown","failed").contains(state)||!n.get("result").isNull()||state.equals("failed")&&error.isNull())throw unknown();
        return new Receipt(id,state,null);
    }
    private TransferResult terminal(Receipt receipt) {
        return switch(receipt.state()) {case "succeeded"->receipt.result();case "failed"->throw new IngestionExecutionException("元数据执行已返回失败回执",false);case "unknown"->throw unknown();default->null;};
    }
    private static void within(long deadline){if(Thread.currentThread().isInterrupted()||System.nanoTime()-deadline>=0)throw unknown();}
    private static IngestionExecutionException unknown(){return new IngestionExecutionException("执行结果尚未可靠确认，禁止自动重放",true);}
    public record TransferResult(long rowsRead,long rowsWritten,long durationMs) {
        public TransferResult {if(rowsRead<0||rowsWritten<0||durationMs<0)throw new IllegalArgumentException("执行计数无效");}
    }
    public static class IngestionExecutionException extends RuntimeException {
        private final boolean outcomeUnknown;
        public IngestionExecutionException(String message){this(message,true);}
        public IngestionExecutionException(String message,boolean outcomeUnknown){super(message);this.outcomeUnknown=outcomeUnknown;}
        public boolean outcomeUnknown(){return outcomeUnknown;}
    }
}
