package com.aetheris.metadata.publishedkey;

import com.aetheris.metadata.dto.*;
import com.aetheris.metadata.entity.*;
import com.aetheris.metadata.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.*;
import static com.aetheris.metadata.publishedkey.PublishedKeyContract.*;

@Service
public final class PublishedKeyQueryService {
    private final ApiAssetService assets;private final MetadataTableService tables;private final MetricDefinitionService metrics;
    private final BusinessDomainService domains;private final TableDataQueryService tableQueries;private final PublishedKeyPeers peers;private final QueryAuditService audit;private final Clock clock;
    @Autowired public PublishedKeyQueryService(ApiAssetService assets,MetadataTableService tables,MetricDefinitionService metrics,BusinessDomainService domains,
            TableDataQueryService tableQueries,PublishedKeyPeers peers,QueryAuditService audit){this(assets,tables,metrics,domains,tableQueries,peers,audit,Clock.systemUTC());}
    public PublishedKeyQueryService(ApiAssetService assets,MetadataTableService tables,MetricDefinitionService metrics,BusinessDomainService domains,
            TableDataQueryService tableQueries,PublishedKeyPeers peers,QueryAuditService audit,Clock clock){
        this.assets=assets;this.tables=tables;this.metrics=metrics;this.domains=domains;this.tableQueries=tableQueries;this.peers=peers;this.audit=audit;this.clock=clock;
    }
    public Asset resolve(String code){var found=assets.publishedByCode(code);if(found==null||!"published".equals(found.getStatus())||!"POST".equals(found.getHttpMethod()))throw new ApiAssetService.NotFoundException("API资产不存在或未发布");return Asset.from(found);}
    public Map<String,Object> query(Reference ref){
        var input=peers.redeem(ref);input.requireLive(clock);var asset=input.asset();long started=System.nanoTime();Long datasourceId=null;String sql=null;Map<String,Object> result=null;boolean success=false;
        try{
            requireCurrent(asset);
            MetricDefinition metric=null;MetadataTable table;Target target;
            if(asset.targetType().equals("table")){table=requireTable(Long.parseLong(asset.targetRef()));target=target("table",table.getId(),table.getBusinessDomainId());}
            else{metric=metrics.requirePublished(asset.targetRef());if(metric==null||metric.getId()==null)throw new Rejected();table=requireTable(metric.getSourceTableId());target=target("metric",metric.getId(),metric.getBusinessDomainId());}
            datasourceId=table.getDatasourceId();peers.authorize(ref,input,target);requireCurrent(asset);input.requireLive(clock);
            var p=input.parameters();
            if(metric==null){
                var plan=tableQueries.prepare(table,new TableQueryRequest(p.columns(),p.limit()));sql=plan.sql();datasourceId=plan.source().getId();
                input.requireLive(clock);result=tableQueries.execute(plan);
            }else{
                // Use the same published snapshot whose ID/domain was authorized, never resolve the code a second time.
                result=metrics.queryPublished(metric,new MetricQueryRequest(p.dimensions(),p.timeGrain(),p.startDate(),p.endDate(),p.limit()));
                if(result!=null&&result.get("generatedSql") instanceof String generated)sql=generated;
            }
            if(result==null||result.containsKey("error"))throw new IllegalStateException("Published query failed");success=true;return result;
        }finally{
            try{Long rows=result!=null&&result.get("rowCount") instanceof Number n?n.longValue():null;
                audit.recordPublished("published_api",asset.assetCode(),asset.id(),datasourceId,input.owner().username(),success?"success":"failed",rows,
                        (System.nanoTime()-started)/1_000_000,sql,input.keyId(),input.admissionId(),input.requestId(),input.owner().uid());
            }catch(RuntimeException ignored){/* Audit cannot replay or alter the completed one-use query. */}
        }
    }
    private void requireCurrent(Asset asset){try{if(!resolve(asset.assetCode()).equals(asset))throw new Rejected();}catch(ApiAssetService.NotFoundException missing){throw new Rejected();}}
    private MetadataTable requireTable(Long id){var table=id==null?null:tables.getById(id);if(table==null||table.getId()==null||!table.getId().equals(id)||"stale".equalsIgnoreCase(table.getStatus()))throw new Rejected();return table;}
    private Target target(String kind,long id,Long domainId){
        if(domainId==null)return new Target(kind,id,null,null);var domain=domains.get(domainId);
        if(domain==null||!domainId.equals(domain.getId()))throw new Rejected();return new Target(kind,id,domainId,domain.getDomainCode());
    }
}
