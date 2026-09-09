package com.aetheris.metadata.controller;

import com.aetheris.metadata.service.LineageParseService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Stores execution-bound lineage evidence and provides bounded impact traversal. */
@RestController
@RequestMapping("/internal/lineage")
public class InternalLineageEvidenceController {
    private static final Pattern TABLE = Pattern.compile(
            "^[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*){0,2}$");
    private final JdbcTemplate jdbc;
    private final LineageParseService parser;
    private final ObjectMapper mapper;

    public InternalLineageEvidenceController(JdbcTemplate jdbc, LineageParseService parser,
                                             ObjectMapper mapper) {
        this.jdbc = jdbc; this.parser = parser; this.mapper = mapper;
    }

    @PostMapping("/executions")
    public Map<String, Object> record(@RequestBody Evidence input) {
        List<String> sources = input.sourceTables() == null ? List.of() : input.sourceTables();
        if (input.executionKey() == null || input.executionKey().length() > 96
                || input.taskId() <= 0 || input.attemptId() <= 0) {
            throw new IllegalArgumentException("执行证据标识不合法");
        }
        if (input.targetTable() != null && !TABLE.matcher(input.targetTable()).matches()) {
            throw new IllegalArgumentException("目标表标识不合法");
        }
        if (sources.stream().anyMatch(source -> source == null || !TABLE.matcher(source).matches())) {
            throw new IllegalArgumentException("来源表标识不合法");
        }
        String parseStatus = "unknown";
        if (input.sql() != null && !input.sql().isBlank()) {
            parser.parseAndSave(input.sql(), "execution");
            parseStatus = "parsed";
        } else if (input.targetTable() != null && !sources.isEmpty()) {
            for (String source : sources) {
                jdbc.update("""
                        INSERT INTO metadata.lineage_relation
                            (source_table,target_table,relation_type,description,source)
                        SELECT ?,?,'execution','受管执行声明','parsed'
                        WHERE NOT EXISTS (
                            SELECT 1 FROM metadata.lineage_relation
                            WHERE source_table=? AND target_table=?
                        )
                        """, source, input.targetTable(), source, input.targetTable());
            }
            parseStatus = "declared";
        }
        jdbc.update("""
                INSERT INTO metadata.lineage_execution_evidence
                    (execution_key,task_id,attempt_id,biz_date,task_type,definition_digest,
                     source_tables,target_table,output_uri,parse_status)
                VALUES (?,?,?,?,?,?,?::jsonb,?,?,?)
                ON CONFLICT (execution_key) DO NOTHING
                """, input.executionKey(), input.taskId(), input.attemptId(), input.bizDate(),
                input.taskType(), input.definitionDigest(), json(sources), input.targetTable(),
                input.outputUri(), parseStatus);
        return Map.of("recorded", true, "parseStatus", parseStatus);
    }

    @GetMapping("/impact")
    public Map<String, Object> impact(@RequestParam String table) {
        if (!TABLE.matcher(table).matches()) throw new IllegalArgumentException("表标识不合法");
        List<String> downstream = jdbc.query("""
                WITH RECURSIVE impact(target_table,depth,path) AS (
                    SELECT target_table,1,ARRAY[source_table,target_table]
                    FROM metadata.lineage_relation WHERE source_table=?
                    UNION ALL
                    SELECT relation.target_table,impact.depth+1,impact.path || relation.target_table
                    FROM metadata.lineage_relation relation JOIN impact
                      ON relation.source_table=impact.target_table
                    WHERE impact.depth<20 AND NOT relation.target_table=ANY(impact.path)
                ) SELECT DISTINCT target_table FROM impact ORDER BY target_table LIMIT 500
                """, (rs, row) -> rs.getString(1), table);
        return Map.of("table", table, "downstreamTables", downstream,
                "truncated", downstream.size() >= 500);
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("执行证据无法序列化", error); }
    }

    public record Evidence(String executionKey, long taskId, long attemptId, String bizDate,
                           String taskType, String definitionDigest, List<String> sourceTables,
                           String targetTable, String outputUri, String sql) { }
}
