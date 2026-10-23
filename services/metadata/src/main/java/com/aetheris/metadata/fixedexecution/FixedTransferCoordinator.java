package com.aetheris.metadata.fixedexecution;

import com.aetheris.security.protocol.BodyHash;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import java.time.Duration;
import static com.aetheris.metadata.fixedexecution.FixedJson.*;

@Service
public class FixedTransferCoordinator {
    private final FixedReceiptStore store;private final FixedExecutionAdapters adapters;private final Duration lifetime;
    @Autowired public FixedTransferCoordinator(FixedReceiptStore store,FixedExecutionAdapters adapters,
            @Value("${aetheris.fixed-execution.receipt-lifetime:PT30M}") Duration lifetime){
        require(lifetime!=null&&lifetime.compareTo(Duration.ofSeconds(10))>=0&&lifetime.compareTo(Duration.ofHours(1))<=0);
        this.store=store;this.adapters=adapters;this.lifetime=lifetime;
    }
    public FixedTransferCoordinator(FixedReceiptStore store,FixedTransferPeers peers,FixedTransferExecution execution,Duration lifetime){this(store,FixedExecutionAdapters.transferOnly(peers,execution),lifetime);}
    public FixedReceiptStore.Receipt submit(byte[] raw){return submit(FixedExecutionOperation.TRANSFER,raw);}
    public FixedReceiptStore.Receipt submit(FixedExecutionOperation operation,byte[] raw){
        var input=FixedExecutionAdapter.Submission.parse(operation,raw);var existing=store.find(operation,input.runId(),input.attemptId(),input.fence());
        if(existing.isPresent())return store.receipt(store.requireCompatible(existing.get(),operation,input));
        var adapter=adapters.get(operation);var manifest=adapter.resolveAndAuthorize(input);var prepared=adapter.prepare(manifest);
        return store.receipt(store.insert(manifest,prepared.normalized(),BodyHash.sha256(raw),lifetime));
    }
    public FixedReceiptStore.Receipt status(byte[] raw){return status(FixedExecutionOperation.TRANSFER,raw);}
    public FixedReceiptStore.Receipt status(FixedExecutionOperation operation,byte[] raw){
        require(raw!=null&&raw.length<=65536);var n=parse(raw);fields(n,"receiptId","runId","attemptId","fence","operation");
        long run=number(n,"runId"),attempt=number(n,"attemptId"),fence=number(n,"fence");operation.validate(run,attempt,fence);require(operation.operation().equals(text(n,"operation",64)));
        Long id=n.get("receiptId").isNull()?null:number(n,"receiptId");require(id==null||id>0);
        var row=store.find(operation,run,attempt,fence).orElseThrow(NotFound::new);if(id!=null&&id!=row.id())throw new FixedReceiptStore.Conflict();return store.receipt(row);
    }
    public static final class NotFound extends RuntimeException { }
}
