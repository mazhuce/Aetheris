package com.aetheris.metadata.fixedexecution;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.*;
import static com.aetheris.metadata.fixedexecution.FixedJson.*;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record FixedSqlResult(Long affectedRows,long durationMs) implements FixedExecutionAdapter.ExecutionResult {
    public FixedSqlResult{require((affectedRows==null||affectedRows>=0)&&durationMs>=0);}
    static FixedSqlResult fromJdbc(Map<String,Object> result){
        require(result!=null&&result.keySet().equals(Set.of("success","affectedRows","durationMs"))&&Boolean.TRUE.equals(result.get("success")));
        long rows=integer(result.get("affectedRows")),duration=integer(result.get("durationMs"));require(rows>=-1&&duration>=0);
        return new FixedSqlResult(rows==-1?null:rows,duration);
    }
    private static long integer(Object n){
        if(n instanceof Byte||n instanceof Short||n instanceof Integer||n instanceof Long)return ((Number)n).longValue();
        if(n instanceof java.math.BigInteger b)try{return b.longValueExact();}catch(ArithmeticException overflow){throw invalid();}
        throw invalid();
    }
}
