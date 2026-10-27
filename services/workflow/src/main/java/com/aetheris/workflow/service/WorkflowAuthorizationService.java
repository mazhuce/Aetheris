package com.aetheris.workflow.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.aetheris.workflow.security.ResourceAuthorization;

import java.util.List;

/** Object-level read authorization for execution details and logs. */
@Service
public class WorkflowAuthorizationService {
    private final JdbcTemplate jdbc;
    private final ResourceAuthorization auth;
    public WorkflowAuthorizationService(JdbcTemplate jdbc,ResourceAuthorization auth){this.jdbc=java.util.Objects.requireNonNull(jdbc);this.auth=java.util.Objects.requireNonNull(auth);}
    private boolean current(String username){return username!=null&&username.equals(auth.username());}

    public boolean canReadInstance(String username, long instanceId) {
        if (!current(username)) return false;
        List<Long> ids = jdbc.queryForList("SELECT task_id FROM workflow.task_instance WHERE id=?", Long.class, instanceId);
        return ids.size() == 1 && canReadTask(username, ids.getFirst());
    }

    public boolean canReadPipeline(String username, long pipelineId) {
        if (!current(username)) return false;
        return pipelineAllowed(username, pipelineId, "view");
    }

    public boolean canReadTask(String username, long taskId) { return checkTasks(username, List.of(taskId), "view"); }
    public boolean canExecuteTasks(String username, List<Long> taskIds) { return checkTasks(username, taskIds, "execute"); }
    public boolean canExecuteInstance(String username, long instanceId) {
        if(!current(username))return false;
        List<Long> ids = jdbc.queryForList("SELECT task_id FROM workflow.task_instance WHERE id=?", Long.class, instanceId);
        return ids.size() == 1 && checkTasks(username, ids, "execute");
    }
    public boolean canExecutePipeline(String username, long pipelineId) { return pipelineAllowed(username, pipelineId, "execute"); }
    private boolean pipelineAllowed(String username, long id, String action) {
        if(!current(username))return false;
        List<Long> ids = jdbc.queryForList("""
                SELECT target_task_id FROM workflow.pipeline_run WHERE id=?
                UNION SELECT task_id FROM workflow.pipeline_node_run WHERE pipeline_run_id=?
                """, Long.class, id, id);
        return !ids.isEmpty() && checkTasks(username, ids, action);
    }
    /** The current dependency closure, including the target, must be executable. */
    public boolean canStartPipeline(String username, long targetId) {
        if(!current(username))return false;
        List<Long> ids = jdbc.queryForList("""
                WITH RECURSIVE tasks(id) AS (
                    SELECT id FROM workflow.task WHERE id=?
                    UNION SELECT dependency.upstream_task_id FROM workflow.task_dependency dependency
                    JOIN tasks ON dependency.task_id=tasks.id)
                SELECT id FROM tasks
                """, Long.class, targetId);
        return !ids.isEmpty() && checkTasks(username, ids, "execute");
    }
    private boolean checkTasks(String username,List<Long> ids,String action){return current(username)&&auth.allTasksAllowed(ids,action);}
    public boolean mayManage(String username){return current(username)&&auth.mayManage();}
    public com.aetheris.workflow.fixedexecution.WorkflowActor pipelineActor(String username){
        if(!current(username))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"流水线主体不匹配");
        return com.aetheris.workflow.fixedexecution.WorkflowActor.pipeline(auth.currentUser());
    }
}
