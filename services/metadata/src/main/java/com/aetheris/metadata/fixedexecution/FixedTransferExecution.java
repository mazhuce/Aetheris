package com.aetheris.metadata.fixedexecution;

import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.service.DataSourceService;
import com.aetheris.metadata.service.JdbcService;
import com.aetheris.metadata.service.DataSourceAccessPolicy;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.aetheris.metadata.fixedexecution.FixedJson.*;

/** Persist only derived execution parameters and connection revisions, never connection credentials. */
@Service
public class FixedTransferExecution {
    private final DataSourceService sources;private final JdbcService jdbc;
    public FixedTransferExecution(DataSourceService sources,JdbcService jdbc){this.sources=sources;this.jdbc=jdbc;}
    public record Prepared(DataSource source,DataSource target,String normalized,FixedTransferManifest.Definition definition){ }
    public Prepared prepare(FixedTransferManifest manifest){
        return prepare(manifest.definition());
    }
    private Prepared prepare(FixedTransferManifest.Definition d){
        DataSource source=sources.getById(d.sourceDatasourceId()),target=sources.getById(d.targetDatasourceId());
        business(source);business(target);
        if(!Objects.equals(source.getId(),d.sourceDatasourceId())||!Objects.equals(target.getId(),d.targetDatasourceId()))throw new FixedTransferPeers.Rejected("DATASOURCE_NOT_ADMITTED");
        if(!target.isWriteConfigured()||!Set.of("postgresql","greenplum","opengauss","cockroachdb","yugabytedb","timescaledb","mysql","mariadb","tidb","oceanbase","doris","starrocks").contains(target.getType()==null?"":target.getType().toLowerCase(Locale.ROOT)))throw new FixedTransferPeers.Rejected("DATASOURCE_NOT_ADMITTED");
        var execution=new LinkedHashMap<String,Object>();execution.put("schemaVersion",1);execution.put("sourceDatasourceId",d.sourceDatasourceId());execution.put("targetDatasourceId",d.targetDatasourceId());
        execution.put("sourceConnectionRevision",source.getConnectionRevision());execution.put("targetConnectionRevision",target.getConnectionRevision());
        execution.put("sourceSql",d.sourceSql());execution.put("targetTable",d.targetTable());execution.put("writeMode",d.writeMode());execution.put("batchSize",d.batchSize());
        return new Prepared(source,target,string(execution),d);
    }
    private static void business(DataSource source){
        if(!DataSourceAccessPolicy.isActiveBusinessSource(source)||source.getConnectionRevision()==null||source.getConnectionRevision()<0
                ||source.getUsername()==null||source.getUsername().isBlank()||source.getPassword()==null||source.getPassword().isBlank())throw new FixedTransferPeers.Rejected("DATASOURCE_NOT_ADMITTED");
    }
    public Prepared verify(FixedTransferManifest manifest,String stored){var p=prepare(manifest);if(!parse(p.normalized()).equals(parse(stored)))throw new FixedTransferPeers.Rejected("DATASOURCE_REVISION_CHANGED");return p;}
    public FixedReceiptStore.Result execute(Prepared p){
        var fresh=prepare(p.definition());
        if(!parse(fresh.normalized()).equals(parse(p.normalized())))throw new FixedTransferPeers.Rejected("DATASOURCE_REVISION_CHANGED");
        var d=fresh.definition();var result=jdbc.transfer(fresh.source(),fresh.target(),d.sourceSql(),d.targetTable(),d.writeMode(),d.batchSize());
        var n=parse(bytes(result));fields(n,"success","rowsRead","rowsWritten","durationMs");require(bool(n,"success"));
        return new FixedReceiptStore.Result(number(n,"rowsRead"),number(n,"rowsWritten"),number(n,"durationMs"));
    }
}
