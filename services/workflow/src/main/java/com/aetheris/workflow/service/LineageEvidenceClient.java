package com.aetheris.workflow.service;

import com.aetheris.workflow.entity.Task;
import com.aetheris.workflow.service.ExecutionClaimCoordinator.ExecutionClaim;
import com.aetheris.workflow.service.ManagedSparkCatalogService.SparkJobView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Best-effort audit evidence writer; execution success is not rolled back by lineage downtime. */
@Component
public class LineageEvidenceClient {
    private static final Logger log = LoggerFactory.getLogger(LineageEvidenceClient.class);
    private final RestClient client;

    public LineageEvidenceClient(RestClient.Builder builder,
                                 @Value("${aetheris.metadata.base-url:http://localhost:8082}") String baseUrl,
                                 @Value("${aetheris.internal-token:}") String token) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(5000);
        client = builder.requestFactory(factory).baseUrl(baseUrl)
                .defaultHeader("X-Internal-Token", token == null ? "" : token).build();
    }

    public void sql(Task task, ExecutionClaim claim, String resolvedSql) {
        record(task, claim, List.of(), task.getTargetTable(), null, resolvedSql, null);
    }

    public void spark(long taskId, ExecutionClaim claim, SparkJobView job) {
        Task task = new Task();
        task.setId(taskId);
        task.setTaskType("spark");
        record(task, claim, job.inputTables(), job.outputTable(), job.outputUri(), null,
                job.definitionDigest());
    }

    private void record(Task task, ExecutionClaim claim, List<String> sources, String target,
                        String outputUri, String sql, String digest) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("executionKey", "workflow-attempt-" + claim.attemptId());
            body.put("taskId", task.getId());
            body.put("attemptId", claim.attemptId());
            body.put("bizDate", claim.instance().getBizDate());
            body.put("taskType", task.getTaskType() == null ? "sql" : task.getTaskType());
            body.put("sourceTables", sources);
            body.put("targetTable", target);
            body.put("outputUri", outputUri);
            body.put("sql", sql);
            body.put("definitionDigest", digest);
            client.post().uri("/internal/lineage/executions").body(body)
                    .retrieve().toBodilessEntity();
        } catch (RuntimeException error) {
            log.warn("Could not persist lineage evidence for attempt {}: {}",
                    claim.attemptId(), error.getMessage());
        }
    }
}
