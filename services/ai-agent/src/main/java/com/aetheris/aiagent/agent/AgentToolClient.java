package com.aetheris.aiagent.agent;

import com.aetheris.aiagent.llm.AiRunContext;
import com.aetheris.aiagent.security.AiProofContext;
import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.configuration.ProtocolInstallation;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/** Fixed, user-bound metadata tools. No URL/header-map/username fallback exists. */
@Component
public class AgentToolClient {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final SignedHttpClient transport;private final ProtocolVerifier verifier;private final Clock clock;
    @Autowired public AgentToolClient(@Qualifier("aiMetadataTransport") SignedHttpClient transport,ProtocolInstallation installation){this(transport,installation.verifier(),Clock.systemUTC());}
    public AgentToolClient(SignedHttpClient transport,ProtocolVerifier verifier,Clock clock){this.transport=Objects.requireNonNull(transport);this.verifier=Objects.requireNonNull(verifier);this.clock=Objects.requireNonNull(clock);}
    public List<Map<String,Object>> searchTables(String keyword,String layer){
        var body=new LinkedHashMap<String,Object>();body.put("keyword",keyword==null?"":keyword);body.put("layer",layer==null?"":layer);
        return list("agent/tables/search","metadata.agent.search",body);
    }
    public Map<String,Object> describeTable(Long tableId){return tableId==null?invalid():map("agent/tables/describe","metadata.agent.describe",Map.of("tableId",tableId));}
    public Map<String,Object> query(Long datasourceId,String sql){return datasourceId==null||sql==null?invalid():map("agent/query","metadata.user.sql-query",Map.of("datasourceId",datasourceId,"sql",sql));}
    public List<Map<String,Object>> searchMetrics(String keyword){return list("semantic/metrics/list","metadata.agent.metric-list",Map.of("keyword",keyword==null?"":keyword));}
    public Map<String,Object> queryMetric(String code,List<String> dimensions,String grain,String start,String end){
        if(code==null||(dimensions!=null&&dimensions.stream().anyMatch(Objects::isNull)))return invalid();
        Map<String,Object> query=new LinkedHashMap<>();query.put("dimensions",dimensions==null?List.of():List.copyOf(dimensions));
        query.put("timeGrain",grain);query.put("startDate",start);query.put("endDate",end);query.put("limit",100);
        return map("semantic/metrics/query","metadata.user.metric-query",Map.of("metricCode",code,"query",query));
    }
    private byte[] invoke(String path,String operation,Object body) throws Exception {
        AiRunContext run=AiRunContext.current();var identity=AiProofContext.current();
        if(run==null||identity==null)throw new IllegalStateException("Missing admitted AI run");
        run.check();var proof=verifier.verifyUser(identity.compact(),"ai-agent").proof();
        if(proof.expiresAt()<=clock.instant().getEpochSecond() || !proof.equals(identity.user().proof()))throw new IllegalStateException("Expired AI identity");
        byte[] raw=JSON.writeValueAsBytes(body);run.check();
        var future=transport.sendAsync(new SignedHttpClient.Request("metadata","POST","/internal/v2/"+path,operation,raw,identity.compact()));
        try {
            long proofMillis=Math.max(1,(proof.expiresAt()-clock.instant().getEpochSecond())*1000);
            var response=future.get(Math.min(45000,Math.min(run.remainingMillis(),proofMillis)),TimeUnit.MILLISECONDS);
            run.check();if(proof.expiresAt()<=clock.instant().getEpochSecond())throw new IllegalStateException("Expired AI identity");
            if(response.status()!=200)throw new IllegalStateException("Metadata tool rejected request");
            return response.body();
        }catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw cancelled;}
        finally{if(!future.isDone())future.cancel(true);}
    }
    private List<Map<String,Object>> list(String path,String operation,Object body){
        try{return JSON.readValue(invoke(path,operation,body),new TypeReference<List<Map<String,Object>>>(){});}
        catch(Exception rejected){return List.of();}
    }
    private Map<String,Object> map(String path,String operation,Object body){
        try {var result=JSON.readValue(invoke(path,operation,body),new TypeReference<Map<String,Object>>(){});return result==null?Map.of("error","工具未返回结果"):result;}
        catch(Exception rejected){return Map.of("error","工具请求被拒绝或暂不可用");}
    }
    private static Map<String,Object> invalid(){return Map.of("error","工具请求参数不完整");}
}
