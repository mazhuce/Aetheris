package com.aetheris.metadata.fixedexecution;
import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.service.*;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.aetheris.metadata.fixedexecution.FixedJson.*;

@Service
public class FixedSqlExecution {
    private final DataSourceService sources;private final JdbcService jdbc;
    public FixedSqlExecution(DataSourceService sources,JdbcService jdbc){this.sources=sources;this.jdbc=jdbc;}
    private DataSource source(FixedSqlManifest manifest){
        var source=sources.getById(manifest.definition().datasourceId());
        if(!DataSourceAccessPolicy.isActiveBusinessSource(source)||!Objects.equals(source.getId(),manifest.definition().datasourceId())||source.getConnectionRevision()==null||source.getConnectionRevision()<0||!source.isWriteConfigured()||source.getUsername()==null||source.getUsername().isBlank())throw new FixedTransferPeers.Rejected("DATASOURCE_NOT_ADMITTED");return source;
    }
    private String normalized(FixedSqlManifest manifest,DataSource source){return string(Map.of("schemaVersion",1,"compilerVersion",1,"datasourceId",manifest.definition().datasourceId(),"connectionRevision",source.getConnectionRevision(),"sql",manifest.definition().sql(),"timeoutSeconds",manifest.definition().timeoutSeconds()));}
    public String prepare(FixedSqlManifest manifest){return normalized(manifest,source(manifest));}
    public String verify(FixedSqlManifest manifest,String stored){var normalized=prepare(manifest);if(!parse(normalized).equals(parse(stored)))throw new FixedTransferPeers.Rejected("DATASOURCE_REVISION_CHANGED");return normalized;}
    public FixedSqlResult execute(FixedSqlManifest manifest,String stored,FixedExecutionAdapter.DispatchBudget budget){
        require(budget!=null);var fresh=source(manifest);if(!parse(normalized(manifest,fresh)).equals(parse(stored)))throw new FixedTransferPeers.Rejected("DATASOURCE_REVISION_CHANGED");
        return FixedSqlResult.fromJdbc(jdbc.execute(fresh,manifest.definition().sql(),manifest.definition().timeoutSeconds(),budget.deadline()));
    }
}
