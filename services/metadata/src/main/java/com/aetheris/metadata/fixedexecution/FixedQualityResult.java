package com.aetheris.metadata.fixedexecution;

import java.math.*;
import java.util.*;
import static com.aetheris.metadata.fixedexecution.FixedJson.*;

public record FixedQualityResult(long violationCount,long durationMs) implements FixedExecutionAdapter.ExecutionResult {
    public FixedQualityResult{require(violationCount>=0&&durationMs>=0);}
    /** JdbcService returns positional rows; one column and one scalar are mandatory for both contracts. */
    static FixedQualityResult fromJdbc(Map<String,Object> result,String ruleType,long durationMs){
        require(result!=null&&result.keySet().equals(Set.of("columns","rows","rowCount","truncated")));
        require(result.get("columns") instanceof List<?>);var columns=(List<?>)result.get("columns");
        require(columns.size()==1&&columns.getFirst() instanceof String&&!((String)columns.getFirst()).isBlank());
        require("sql".equals(ruleType)||"violation_count".equalsIgnoreCase((String)columns.getFirst()));
        require(result.get("rows") instanceof List<?>);var rows=(List<?>)result.get("rows");require(rows.size()==1&&rows.getFirst() instanceof List<?>);
        var row=(List<?>)rows.getFirst();require(row.size()==1&&integer(result.get("rowCount"))==1&&Boolean.FALSE.equals(result.get("truncated")));
        return new FixedQualityResult(integer(row.getFirst()),durationMs);
    }
    private static long integer(Object value){
        try{
            long number;
            if(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long)number=((Number)value).longValue();
            else if(value instanceof BigInteger big)number=big.longValueExact();
            else if(value instanceof BigDecimal big&&big.scale()<=0)number=big.longValueExact();
            else throw invalid();require(number>=0);return number;
        }catch(ArithmeticException overflow){throw invalid();}
    }
}
