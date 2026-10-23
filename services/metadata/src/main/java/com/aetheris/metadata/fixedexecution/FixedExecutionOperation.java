package com.aetheris.metadata.fixedexecution;

/** Server-owned directory: callers/paths are resolved before any body is interpreted. */
public enum FixedExecutionOperation {
    TRANSFER("ingestion","metadata.fixed-transfer.submit"),
    QUALITY("quality","metadata.fixed-quality-query.submit"),
    SQL("workflow","metadata.fixed-sql.submit");
    private final String origin,operation;
    FixedExecutionOperation(String origin,String operation){this.origin=origin;this.operation=operation;}
    public String origin(){return origin;}
    public String operation(){return operation;}
    public static FixedExecutionOperation caller(String caller){
        return switch(caller){case "ingestion"->TRANSFER;case "quality"->QUALITY;case "workflow"->SQL;default->throw new SecurityException();};
    }
    void validate(long run,long attempt,long fence){FixedJson.require(run>0&&(this==SQL?attempt>0&&fence>0:attempt==0&&fence==1));}
    void validate(FixedExecutionAdapter.Submission input){FixedJson.require(input!=null);validate(input.runId(),input.attemptId(),input.fence());}
    static FixedExecutionOperation stored(String origin,String operation){
        for(var item:values())if(item.origin.equals(origin)&&item.operation.equals(operation))return item;throw FixedJson.invalid();
    }
    FixedExecutionAdapter.ExecutionResult result(String raw){
        var n=FixedJson.parse(raw);
        if(this==SQL){FixedJson.fields(n,"affectedRows","durationMs");return new FixedSqlResult(n.get("affectedRows").isNull()?null:FixedJson.number(n,"affectedRows"),FixedJson.number(n,"durationMs"));}
        if(this==QUALITY){FixedJson.fields(n,"violationCount","durationMs");return new FixedQualityResult(FixedJson.number(n,"violationCount"),FixedJson.number(n,"durationMs"));}
        FixedJson.fields(n,"rowsRead","rowsWritten","durationMs");return new FixedReceiptStore.Result(FixedJson.number(n,"rowsRead"),FixedJson.number(n,"rowsWritten"),FixedJson.number(n,"durationMs"));
    }
}
