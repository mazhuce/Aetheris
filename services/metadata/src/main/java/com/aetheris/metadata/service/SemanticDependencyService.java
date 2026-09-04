package com.aetheris.metadata.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class SemanticDependencyService {
    private final JdbcTemplate jdbc; private final ObjectMapper objectMapper;
    public SemanticDependencyService(JdbcTemplate jdbc,ObjectMapper objectMapper){this.jdbc=jdbc;this.objectMapper=objectMapper;}
    public Map<String,Object> graph(){List<Map<String,Object>> nodes=new ArrayList<>(),edges=new ArrayList<>();Set<String> seen=new HashSet<>();
        jdbc.query("SELECT id, schema_name, table_name FROM metadata.metadata_table WHERE status <> 'stale' ORDER BY id LIMIT 5000",rs->{while(rs.next()){String label=(rs.getString("schema_name") == null || rs.getString("schema_name").isBlank() ? "" : rs.getString("schema_name") + ".") + rs.getString("table_name");node(nodes,seen,"table:"+rs.getLong("id"),"table",label);}});
        jdbc.query("SELECT id, metric_code, source_table_id, logical_model_id FROM metadata.metric_definition WHERE status <> 'deprecated'",rs->{while(rs.next()){String metric="metric:"+rs.getLong("id");node(nodes,seen,metric,"metric",rs.getString("metric_code"));if(rs.getObject("source_table_id")!=null)edge(edges,metric,"table:"+rs.getLong("source_table_id"),"source_table");if(rs.getObject("logical_model_id")!=null)edge(edges,metric,"model:"+rs.getLong("logical_model_id"),"logical_model");}});
        jdbc.query("SELECT id, dimension_code, metadata_table_id FROM metadata.semantic_dimension WHERE status <> 'deprecated'",rs->{while(rs.next()){String dim="dimension:"+rs.getLong("id");node(nodes,seen,dim,"dimension",rs.getString("dimension_code"));edge(edges,dim,"table:"+rs.getLong("metadata_table_id"),"metadata_table");}});
        jdbc.query("SELECT id, model_code, entities_json FROM metadata.logical_model WHERE status <> 'deprecated'",rs->{while(rs.next()){String model="model:"+rs.getLong("id");node(nodes,seen,model,"logical_model",rs.getString("model_code"));try{JsonNode root=objectMapper.readTree(rs.getString("entities_json"));root.path("entities").forEach(e->{if(e.has("tableId"))edge(edges,model,"table:"+e.get("tableId").asLong(),"entity_table");});}catch(Exception ignored){}}});
        return Map.of("nodes",nodes,"edges",edges);
    }
    private void node(List<Map<String,Object>> nodes,Set<String> seen,String id,String type,String label){if(seen.add(id)){Map<String,Object> value=new LinkedHashMap<>();value.put("id",id);value.put("type",type);value.put("label",label==null?id:label);nodes.add(value);}}
    private void edge(List<Map<String,Object>> edges,String from,String to,String relation){edges.add(Map.of("from",from,"to",to,"relation",relation));}
}
