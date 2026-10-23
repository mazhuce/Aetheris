package com.aetheris.metadata.fixedexecution;

import org.springframework.stereotype.Component;
import java.util.*;
import static com.aetheris.metadata.fixedexecution.FixedJson.*;
import static com.aetheris.metadata.fixedexecution.FixedExecutionAdapter.*;

/** The application directory is fixed at construction; payloads cannot register or select URLs. */
@Component
public final class FixedExecutionAdapters {
    private final Map<FixedExecutionOperation,FixedExecutionAdapter> adapters;
    @org.springframework.beans.factory.annotation.Autowired public FixedExecutionAdapters(FixedTransferPeers transfer,FixedTransferExecution transferExecution,FixedQualityPeers quality,FixedQualityExecution qualityExecution,FixedSqlPeers sql,FixedSqlExecution sqlExecution){
        this(Map.of(FixedExecutionOperation.TRANSFER,transfer(transfer,transferExecution),FixedExecutionOperation.QUALITY,quality(quality,qualityExecution),FixedExecutionOperation.SQL,sql(sql,sqlExecution)));
    }
    public FixedExecutionAdapters(FixedTransferPeers transfer,FixedTransferExecution transferExecution,FixedQualityPeers quality,FixedQualityExecution qualityExecution){this(Map.of(FixedExecutionOperation.TRANSFER,transfer(transfer,transferExecution),FixedExecutionOperation.QUALITY,quality(quality,qualityExecution)));}
    static FixedExecutionAdapters sqlOnly(FixedSqlPeers peers,FixedSqlExecution execution){return new FixedExecutionAdapters(Map.of(FixedExecutionOperation.SQL,sql(peers,execution)));}
    private FixedExecutionAdapters(Map<FixedExecutionOperation,FixedExecutionAdapter> adapters){this.adapters=Map.copyOf(adapters);}
    static FixedExecutionAdapters transferOnly(FixedTransferPeers peers,FixedTransferExecution execution){return new FixedExecutionAdapters(Map.of(FixedExecutionOperation.TRANSFER,transfer(peers,execution)));}
    public FixedExecutionAdapter get(FixedExecutionOperation operation){var result=adapters.get(operation);if(result==null)throw new SecurityException();return result;}
    private static FixedExecutionAdapter transfer(FixedTransferPeers peers,FixedTransferExecution execution){return new FixedExecutionAdapter(){
        public FixedExecutionOperation operation(){return FixedExecutionOperation.TRANSFER;}
        private FixedTransferManifest manifest(Invocation i){require(i.operation()==operation());return FixedTransferManifest.parse(i.canonical(),i.submission().manifestSha256(),i.submission().transfer());}
        public Invocation resolveAndAuthorize(Submission input){var m=peers.resolveAndAuthorize(input.transfer());return new Invocation(operation(),input,m.canonical());}
        public Prepared prepare(Invocation i){return new Prepared(i,execution.prepare(manifest(i)).normalized());}
        public Prepared verify(Invocation i,String stored){execution.verify(manifest(i),stored);return new Prepared(i,stored);}
        public ExecutionResult execute(Prepared p){return execution.execute(execution.verify(manifest(p.invocation()),p.normalized()));}
    };}
    private static FixedExecutionAdapter quality(FixedQualityPeers peers,FixedQualityExecution execution){return new FixedExecutionAdapter(){
        public FixedExecutionOperation operation(){return FixedExecutionOperation.QUALITY;}
        private FixedQualityManifest manifest(Invocation i){require(i.operation()==operation());return FixedQualityManifest.parse(i.canonical(),i.submission().manifestSha256(),i.submission());}
        public Invocation resolveAndAuthorize(Submission input){var m=peers.resolveAndAuthorize(input);return new Invocation(operation(),input,m.canonical());}
        public Prepared prepare(Invocation i){return new Prepared(i,execution.prepare(manifest(i)));}
        public Prepared verify(Invocation i,String stored){return new Prepared(i,execution.verify(manifest(i),stored));}
        public ExecutionResult execute(Prepared p){return execution.execute(manifest(p.invocation()),p.normalized());}
    };}
    private static FixedExecutionAdapter sql(FixedSqlPeers peers,FixedSqlExecution execution){return new FixedExecutionAdapter(){
        public FixedExecutionOperation operation(){return FixedExecutionOperation.SQL;}
        private FixedSqlManifest manifest(Invocation i){require(i.operation()==operation());return FixedSqlManifest.parse(i.canonical(),i.submission().manifestSha256(),i.submission());}
        public Invocation resolveAndAuthorize(Submission input){operation().validate(input);var resolved=peers.resolveAndAuthorize(input);return new Invocation(operation(),input,resolved.manifest().canonical(),resolved.lease());}
        public Prepared prepare(Invocation i){return new Prepared(i,execution.prepare(manifest(i)));}
        public Prepared verify(Invocation i,String stored){return new Prepared(i,execution.verify(manifest(i),stored));}
        public ExecutionResult execute(Prepared p){throw new IllegalStateException("SQL dispatch requires its trusted bounded deadline");}
        public ExecutionResult execute(Prepared p,DispatchBudget budget){return execution.execute(manifest(p.invocation()),p.normalized(),budget);}
    };}

}
