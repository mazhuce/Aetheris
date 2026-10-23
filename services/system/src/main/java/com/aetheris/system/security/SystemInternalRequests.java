package com.aetheris.system.security;

import com.aetheris.security.servlet.WorkloadProofFilter;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.Set;

/** Strict, local-only parser: neither the global MVC mapper nor legacy identity headers are trusted. */
@Component
public class SystemInternalRequests {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(4096).maxNumberLength(20).build()).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    private final Validator validator;
    public SystemInternalRequests(Validator validator){this.validator=validator;}
    public String caller(HttpServletRequest request,String operation,Set<String> allowed) {
        final WorkloadProofFilter.TrustedRequest trusted;
        try {trusted=WorkloadProofFilter.trusted(request);}
        catch(IllegalStateException absent){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Verified workload required");}
        if(!trusted.workload().proof().operation().equals(operation)||!allowed.contains(trusted.workload().caller())||trusted.user().isPresent())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Workload operation rejected");
        return trusted.workload().caller();
    }
    public <T> T read(HttpServletRequest request,Class<T> type) {
        try {
            byte[] body=request.getInputStream().readNBytes(16_385);
            if(body.length>16_384)throw new IllegalArgumentException();
            T value=JSON.readValue(body,type);
            if(value==null||!validator.validate(value).isEmpty())throw new IllegalArgumentException();
            return value;
        }catch(Exception invalid){throw new IllegalArgumentException("Invalid internal request body");}
    }
    public com.aetheris.system.dto.OperationsNotificationRequest operationsEvent(HttpServletRequest request) {
        try {
            byte[] body=request.getInputStream().readNBytes(16_385);
            if(body.length>16_384)throw new IllegalArgumentException();
            JsonNode node=JSON.readTree(body);
            Set<String> fields=Set.of("eventId","sourceId","level","title","message","linkPath");
            if(node==null||!node.isObject()||node.size()!=fields.size())throw new IllegalArgumentException();
            node.fieldNames().forEachRemaining(field->{if(!fields.contains(field))throw new IllegalArgumentException();});
            String event=text(node,"eventId",false);
            java.util.UUID id=java.util.UUID.fromString(event);
            if(!id.toString().equals(event))throw new IllegalArgumentException();
            JsonNode source=node.get("sourceId");Long sourceId=null;
            if(!source.isNull()){if(!source.isIntegralNumber()||!source.canConvertToLong()||source.longValue()<=0)throw new IllegalArgumentException();sourceId=source.longValue();}
            return new com.aetheris.system.dto.OperationsNotificationRequest(id,sourceId,text(node,"level",false),text(node,"title",false),text(node,"message",true),text(node,"linkPath",true));
        }catch(Exception invalid){throw new IllegalArgumentException("Invalid operations event body");}
    }
    private static String text(JsonNode node,String field,boolean nullable){
        JsonNode value=node.get(field);
        if(value==null)throw new IllegalArgumentException();
        if(nullable&&value.isNull())return null;
        if(!value.isTextual())throw new IllegalArgumentException();
        return value.textValue();
    }
}
