package com.aetheris.database;

import java.util.*;

/** Exact audited PostgreSQL identity signatures; never emit inventory argument text as SQL. */
final class ReviewedFunctions {
    record Policy(String types, boolean runtimeExecute) { }
    private record Helper(String arguments,String types,String result) { }
    private static final Map<String,Helper> HELPERS=Map.of(
        "execution_identity_valid",new Helper("value jsonb","jsonb","boolean"),
        "execution_actor_valid",new Helper("value jsonb","jsonb","boolean"),
        "execution_approval_valid",new Helper("value jsonb, revision bigint, definition_hash text","jsonb, bigint, text","boolean"),
        "execution_definition_projection",new Helper("body jsonb","jsonb","jsonb"));
    private static final Map<String,Helper> PIPELINE_HELPERS=Map.of(
        "pipeline_plan_current",new Helper("body jsonb","jsonb","boolean"),
        "pipeline_success_evidence",new Helper("node_key bigint","bigint","boolean"),
        "pipeline_retry_ancestor",new Helper("run_key bigint, source_key bigint","bigint, bigint","boolean"),
        "pipeline_dependencies_ready",new Helper("run_key bigint, node_key bigint","bigint, bigint","boolean"),
        "pipeline_binding_valid",new Helper("value workflow.execution_attempt","workflow.execution_attempt","boolean"));
    static Policy review(Map<String,Object> item) {
        String schema=DatabaseRunner.text(item,"schema");DatabaseRunner.runtime(schema);
        if(!Boolean.FALSE.equals(item.get("securityDefiner")))throw new IllegalStateException("Unreviewed function security");
        if(!(item.get("arguments") instanceof String args)||!(item.get("resultType") instanceof String result))throw new IllegalStateException("Missing function type inventory");
        if(args.isEmpty() && result.equals("trigger"))return new Policy("",false);
        Helper helper=HELPERS.get(DatabaseRunner.text(item,"name"));
        if(helper==null&&schema.equals("workflow"))helper=PIPELINE_HELPERS.get(DatabaseRunner.text(item,"name"));
        if(!Set.of("workflow","ingestion","quality").contains(schema)||helper==null||!helper.arguments.equals(args)||!helper.result.equals(result))
            throw new IllegalStateException("Unreviewed function identity");
        return new Policy(helper.types,true);
    }
    static String ddl(Map<String,Object> item) {
        return DatabaseRunner.q(DatabaseRunner.text(item,"schema"))+"."+DatabaseRunner.q(DatabaseRunner.text(item,"name"))+"("+review(item).types+")";
    }
    static void inventory(List<Map<String,Object>> functions) {
        var actual=new HashSet<String>();var expected=new HashSet<String>();
        for(String schema:List.of("workflow","ingestion","quality"))for(String name:HELPERS.keySet())expected.add(schema+"."+name);
        for(String name:PIPELINE_HELPERS.keySet())expected.add("workflow."+name);
        for(var function:functions)if(review(function).runtimeExecute && !actual.add(DatabaseRunner.text(function,"schema")+"."+DatabaseRunner.text(function,"name")))
            throw new IllegalStateException("Duplicate reviewed helper");
        if(!actual.equals(expected))throw new IllegalStateException("Missing fixed execution helper functions");
    }
}
