package com.aetheris.serving.service;

import com.aetheris.serving.dto.ApiAssetQueryRequest;
import com.aetheris.security.protocol.BodyHash;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import static com.aetheris.serving.service.PublishedKeyJson.*;

/** Six fields in this exact order are the version-one parameter hash encoding. */
public record PublishedKeyParameters(List<String> dimensions,String timeGrain,String startDate,String endDate,List<String> columns,int limit) {
    public PublishedKeyParameters {
        dimensions=names(dimensions,8,"[A-Za-z][A-Za-z0-9_]{1,63}");columns=names(columns,50,"[A-Za-z_][A-Za-z0-9_$]*");
        timeGrain=timeGrain==null||timeGrain.isBlank()?null:timeGrain.trim().toLowerCase(Locale.ROOT);
        if(timeGrain!=null&&!Set.of("none","day","month","year").contains(timeGrain))throw bad();
        startDate=date(startDate);endDate=date(endDate);
        if(startDate!=null&&endDate!=null&&LocalDate.parse(startDate).isAfter(LocalDate.parse(endDate)))throw bad();
        if(limit<1||limit>100)throw bad();
    }
    public static PublishedKeyParameters from(ApiAssetQueryRequest value){return value==null?new PublishedKeyParameters(List.of(),null,null,null,List.of(),100):new PublishedKeyParameters(value.dimensions(),value.timeGrain(),value.startDate(),value.endDate(),value.columns(),value.limit()==null?100:value.limit());}
    public static ApiAssetQueryRequest publicRequest(byte[] body) {
        if(body==null||body.length==0)return null;
        JsonNode n=parse(body);if(!n.isObject())throw bad();
        Set<String> allowed=Set.of("dimensions","timeGrain","startDate","endDate","columns","limit");
        n.fieldNames().forEachRemaining(f->{if(!allowed.contains(f))throw bad();});
        Integer limit=null;if(n.hasNonNull("limit")){long l=integer(n,"limit",1);if(l>100)throw bad();limit=(int)l;}
        var result=new ApiAssetQueryRequest(list(n,"dimensions"),nullable(n,"timeGrain"),nullable(n,"startDate"),nullable(n,"endDate"),list(n,"columns"),limit);
        from(result);return result;
    }
    public Map<String,Object> map(){var result=new LinkedHashMap<String,Object>();result.put("dimensions",dimensions);result.put("timeGrain",timeGrain);result.put("startDate",startDate);result.put("endDate",endDate);result.put("columns",columns);result.put("limit",limit);return Collections.unmodifiableMap(result);}
    public String sha256(){return BodyHash.sha256(bytes(map()));}
    private static List<String> names(List<String> input,int maximum,String pattern){
        if(input==null)return List.of();if(input.size()>maximum)throw bad();
        if(input.stream().anyMatch(s->s==null||s.length()>128||!s.matches(pattern)))throw bad();return input.stream().distinct().toList();
    }
    private static String date(String s){if(s==null||s.isBlank())return null;String value=s.trim();if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw bad();try{return LocalDate.parse(value).toString();}catch(DateTimeException invalid){throw bad();}}
    private static String nullable(JsonNode n,String f){if(!n.hasNonNull(f))return null;if(!n.get(f).isTextual())throw bad();return n.get(f).textValue();}
    private static List<String> list(JsonNode n,String f){if(!n.hasNonNull(f))return List.of();if(!n.get(f).isArray())throw bad();var values=new ArrayList<String>();for(var v:n.get(f)){if(!v.isTextual())throw bad();values.add(v.textValue());}return values;}
}
