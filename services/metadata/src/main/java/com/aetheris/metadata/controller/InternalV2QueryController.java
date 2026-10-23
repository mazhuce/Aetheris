package com.aetheris.metadata.controller;

import com.aetheris.metadata.dto.MetricQueryRequest;
import com.aetheris.metadata.dto.TableQueryRequest;
import com.aetheris.metadata.service.MetricDefinitionService;
import com.aetheris.security.servlet.WorkloadProofFilter;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.*;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.*;

/** Strict v2 wire adapter over existing query implementations; all resource authorization remains in those implementations. */
@RestController
@RequestMapping("/internal/v2")
public class InternalV2QueryController {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(20).maxStringLength(200000).build()).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    static {
        var textual=JSON.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual);
        for(var shape:List.of(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
                com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean))
            textual.setCoercion(shape,com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
    }
    private final InternalAgentToolController agent;
    private final InternalTableQueryController tables;
    private final InternalSemanticMetricController metrics;
    private final Validator validator;
    public InternalV2QueryController(InternalAgentToolController agent,InternalTableQueryController tables,
                                     InternalSemanticMetricController metrics,Validator validator) {
        this.agent=agent;this.tables=tables;this.metrics=metrics;this.validator=validator;
    }
    public record Search(@Size(max=128) String keyword,@Size(max=32) String layer) { }
    public record Describe(@NotNull @Positive Long tableId) { }
    public record Sql(@NotNull @Positive Long datasourceId,@NotBlank @Size(max=200000) String sql) { }
    public record Table(@NotNull @Positive Long tableId,@Valid TableQueryRequest query) { }
    public record MetricList(@Size(max=128) String keyword) { }
    public record Metric(@NotBlank @Size(max=128) String metricCode,@Valid MetricQueryRequest query) { }

    @PostMapping("/agent/tables/search") public Object search(HttpServletRequest request,@RequestBody byte[] raw) {
        require(request,"metadata.agent.search");var body=read(raw,Search.class);return agent.searchTables(request,body.keyword(),body.layer());
    }
    @PostMapping("/agent/tables/describe") public Object describe(HttpServletRequest request,@RequestBody byte[] raw) {
        require(request,"metadata.agent.describe");var body=read(raw,Describe.class);return agent.describeTable(request,body.tableId());
    }
    @PostMapping("/agent/query") public Object sql(HttpServletRequest request,@RequestBody byte[] raw) {
        require(request,"metadata.user.sql-query");var body=read(raw,Sql.class);
        return agent.query(request,Map.of("datasourceId",body.datasourceId(),"sql",body.sql()));
    }
    @PostMapping("/tables/query") public Object table(HttpServletRequest request,@RequestBody byte[] raw) {
        require(request,"metadata.user.table-query");var body=read(raw,Table.class);return tables.query(request,body.tableId(),body.query());
    }
    @PostMapping("/semantic/metrics/list") public Object list(HttpServletRequest request,@RequestBody byte[] raw) {
        require(request,"metadata.agent.metric-list");return metrics.list(request,read(raw,MetricList.class).keyword());
    }
    @PostMapping("/semantic/metrics/query") public Object metric(HttpServletRequest request,@RequestBody byte[] raw) {
        require(request,"metadata.user.metric-query");var body=read(raw,Metric.class);return metrics.query(request,body.metricCode(),body.query());
    }
    private <T> T read(byte[] raw,Class<T> type) {
        try {
            T value=JSON.readValue(raw,type);
            if(value==null||!validator.validate(value).isEmpty())throw new IllegalArgumentException("Invalid metadata v2 request");
            return value;
        }catch(IOException invalid){throw new IllegalArgumentException("Invalid metadata v2 request");}
    }
    private static void require(HttpServletRequest request,String operation) {
        try {
            var trusted=WorkloadProofFilter.trusted(request);
            if(!operation.equals(trusted.workload().proof().operation())||trusted.user().isEmpty())throw new SecurityException();
        }catch(IllegalStateException unavailable){throw new SecurityException("Authenticated workload and user required");}
    }
    @ExceptionHandler(SecurityException.class) ResponseEntity<?> unauthenticated() {return ResponseEntity.status(401).body(Map.of("message","Authenticated workload and user required"));}
    @ExceptionHandler(MetricDefinitionService.NotFoundException.class) ResponseEntity<?> notFound() {return ResponseEntity.notFound().build();}
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> badRequest() {return ResponseEntity.badRequest().body(Map.of("message","Invalid metadata query request"));}
    @ExceptionHandler(IllegalStateException.class) ResponseEntity<?> unavailable() {return ResponseEntity.status(502).body(Map.of("message","Metadata query unavailable"));}
}
