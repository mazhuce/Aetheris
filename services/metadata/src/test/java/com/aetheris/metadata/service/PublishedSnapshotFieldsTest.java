package com.aetheris.metadata.service;

import com.aetheris.metadata.dto.SemanticVersion;
import com.aetheris.metadata.entity.MetricDefinition;
import com.aetheris.metadata.mapper.MetricDefinitionMapper;
import com.aetheris.metadata.mapper.VisibleListMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.time.OffsetDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PublishedSnapshotFieldsTest {
    private final ObjectMapper json=new ObjectMapper();
    private final MetricDefinitionMapper mapper=mock(MetricDefinitionMapper.class);
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final MetricDefinitionService service=new MetricDefinitionService(mapper,mock(BusinessDomainService.class),
            mock(LogicalModelService.class),mock(SemanticMetricQueryService.class),jdbc,json);
    private final MetricDefinition aggregate=new MetricDefinition();
    PublishedSnapshotFieldsTest() {
        aggregate.setId(8L);aggregate.setMetricCode("GMV");aggregate.setMetricName("private-draft-name");
        aggregate.setPublishedVersion(1);aggregate.setStatus("draft");aggregate.setVersion(2);
        when(mapper.selectOne(any())).thenReturn(aggregate);
    }
    private ObjectNode snapshot() {
        return json.createObjectNode().put("metricCode","GMV").put("metricName","published-name").put("businessDomainId",0);
    }
    private SemanticVersion version(ObjectNode snapshot) { return new SemanticVersion(20L,1,snapshot,"approver",OffsetDateTime.now()); }
    private void scalarVersion(SemanticVersion version) {
        when(jdbc.query(anyString(),any(RowMapper.class),eq(8L),eq(1))).thenReturn(List.of(version));
    }

    @Test void missingNontextOrBlankIdentityNeverFallsBackToDraftInEitherResolver() {
        for(String field:List.of("metricCode","metricName")) {
            for(int kind=0;kind<5;kind++) {
                var node=snapshot();
                switch(kind) {
                    case 0 -> node.remove(field);
                    case 1 -> node.putNull(field);
                    case 2 -> node.put(field,123);
                    case 3 -> node.set(field,json.createObjectNode());
                    default -> node.put(field," ");
                }
                var active=version(node);scalarVersion(active);
                var error=assertThrows(VisibleListMapper.InvalidSnapshotException.class,()->service.requirePublished("GMV"));
                assertFalse(error.getMessage().contains("private-draft-name"));
                assertThrows(VisibleListMapper.InvalidSnapshotException.class,
                        ()->service.resolvePublishedBatch(List.of(new VisibleListMapper.MetricCandidate(aggregate,active))));
            }
        }
    }

    @Test void mismatchedStableCodeAndInvalidDomainAreRejectedButHistoricalZeroIsUnassigned() {
        for(var node:List.of(snapshot().put("metricCode","OTHER"),snapshot().put("businessDomainId",-1),
                snapshot().put("businessDomainId","1"),snapshot().put("businessDomainId",1.5))) {
            var active=version(node);scalarVersion(active);
            assertThrows(VisibleListMapper.InvalidSnapshotException.class,()->service.requirePublished("GMV"));
            assertThrows(VisibleListMapper.InvalidSnapshotException.class,
                    ()->service.resolvePublishedBatch(List.of(new VisibleListMapper.MetricCandidate(aggregate,active))));
        }
        var active=version(snapshot());scalarVersion(active);
        var scalar=service.requirePublished("GMV");
        var batch=service.resolvePublishedBatch(List.of(new VisibleListMapper.MetricCandidate(aggregate,active))).getFirst();
        assertNull(scalar.getBusinessDomainId());assertNull(batch.getBusinessDomainId());
        assertEquals("published-name",scalar.getMetricName());assertEquals("published-name",batch.getMetricName());
    }
}
