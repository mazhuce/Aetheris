package com.aetheris.workflow.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** Object-level read authorization for execution details and logs. */
@Service
public class WorkflowAuthorizationService {
    private final JdbcTemplate jdbc;
    private final RestClient auth;

    public WorkflowAuthorizationService(JdbcTemplate jdbc, RestClient.Builder builder,
                                        @Value("${aetheris.auth.base-url:http://localhost:8087}") String baseUrl,
                                        @Value("${aetheris.internal-token:}") String token) {
        this.jdbc = jdbc;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(500); factory.setReadTimeout(1500);
        this.auth = builder.requestFactory(factory).baseUrl(baseUrl)
                .defaultHeader("X-Internal-Token", token == null ? "" : token).build();
    }

    public boolean canReadInstance(String username, long instanceId) {
        if (username == null) return false;
        Integer owned = jdbc.queryForObject("""
                SELECT count(*) FROM workflow.task_instance instance
                JOIN workflow.task task ON task.id=instance.task_id
                WHERE instance.id=? AND (task.owner=? OR instance.triggered_by=?)
                """, Integer.class, instanceId, username, username);
        return owned != null && owned > 0 || mayManage(username);
    }

    public boolean canReadPipeline(String username, long pipelineId) {
        if (username == null) return false;
        Integer owned = jdbc.queryForObject("""
                SELECT count(*) FROM workflow.pipeline_run run
                JOIN workflow.task task ON task.id=run.target_task_id
                WHERE run.id=? AND (run.triggered_by=? OR task.owner=?)
                """, Integer.class, pipelineId, username, username);
        return owned != null && owned > 0 || mayManage(username);
    }

    public boolean mayManage(String username) {
        if (username == null) return false;
        try {
            Permission response = auth.post().uri("/internal/permissions/may-manage")
                    .body(Map.of("username", username)).retrieve().body(Permission.class);
            return response != null && response.allowed();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private record Permission(boolean allowed) { }
}
