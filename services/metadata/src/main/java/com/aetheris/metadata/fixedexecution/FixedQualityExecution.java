package com.aetheris.metadata.fixedexecution;

import com.aetheris.metadata.service.*;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.aetheris.metadata.fixedexecution.FixedJson.*;

@Service
public class FixedQualityExecution {
    private final DataSourceService sources;private final JdbcService jdbc;
    public FixedQualityExecution(DataSourceService sources,JdbcService jdbc){this.sources=sources;this.jdbc=jdbc;}
    public String prepare(FixedQualityManifest manifest){
        var source=source(manifest);var d=manifest.definition();return string(Map.of("schemaVersion",1,"generatorVersion",1,"datasourceId",d.datasourceId(),"connectionRevision",source.getConnectionRevision(),"sql",d.sql(),"ruleType",d.ruleType(),"threshold",d.threshold()));
    }
    public String verify(FixedQualityManifest manifest,String stored){String normalized=prepare(manifest);if(!parse(normalized).equals(parse(stored)))throw new FixedTransferPeers.Rejected("DATASOURCE_REVISION_CHANGED");return normalized;}
    private com.aetheris.metadata.entity.DataSource source(FixedQualityManifest m){
        var d=sources.getById(m.definition().datasourceId());
        if(!DataSourceAccessPolicy.isActiveBusinessSource(d)||!Objects.equals(d.getId(),m.definition().datasourceId())||d.getConnectionRevision()==null||d.getConnectionRevision()<0||d.getUsername()==null||d.getUsername().isBlank()||d.getPassword()==null||d.getPassword().isBlank())throw new FixedTransferPeers.Rejected("DATASOURCE_NOT_ADMITTED");return d;
    }
    public FixedQualityResult execute(FixedQualityManifest manifest,String stored){
        // Recheck the same immutable definition and the freshly loaded READ connection immediately before dispatch.
        var source=source(manifest);String actual=string(Map.of("schemaVersion",1,"generatorVersion",1,"datasourceId",manifest.definition().datasourceId(),"connectionRevision",source.getConnectionRevision(),"sql",manifest.definition().sql(),"ruleType",manifest.definition().ruleType(),"threshold",manifest.definition().threshold()));
        if(!parse(actual).equals(parse(stored)))throw new FixedTransferPeers.Rejected("DATASOURCE_REVISION_CHANGED");
        long start=System.nanoTime();var result=jdbc.query(source,manifest.definition().sql());return FixedQualityResult.fromJdbc(result,manifest.definition().ruleType(),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
    }
}
