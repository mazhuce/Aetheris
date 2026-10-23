package com.aetheris.workflow.controller;

import com.aetheris.workflow.fixedexecution.*;
import com.aetheris.workflow.security.ResourceAuthorization;
import com.aetheris.workflow.service.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/workflow/tasks/{id}")
public class WorkflowApprovalController {
    private final WorkflowExecutionStore store;private final ResourceAuthorization authorization;private final SqlExecutionClient client;
    private final TaskSchedulerService scheduler;private final TaskService tasks;
    public WorkflowApprovalController(WorkflowExecutionStore store,ResourceAuthorization authorization,SqlExecutionClient client,TaskSchedulerService scheduler,TaskService tasks){this.store=store;this.authorization=authorization;this.client=client;this.scheduler=scheduler;this.tasks=tasks;}
    public record State(long executionRevision,String definitionSha256,boolean approved,long bindingGeneration,long scheduleGeneration,boolean cronBound){}
    private State state(WorkflowExecutionStore.Definition d){return new State(d.revision(),d.definitionSha256(),d.approved(),d.bindingGeneration(),d.scheduleGeneration(),d.cronBound());}
    @GetMapping("/approval") public State get(@PathVariable long id){authorization.require("workflow_task",id,"view");return state(store.definition(id));}
    @PostMapping("/approve") public State approve(@PathVariable long id,@RequestBody byte[] raw){
        var actor=WorkflowActor.manual(authorization.currentUser());var body=FixedJson.parse(raw,65536);FixedJson.fields(body,"expectedRevision");long revision=FixedJson.number(body,"expectedRevision",1);
        authorization.require("workflow_task",id,"update");var d=store.definition(id);if(d.revision()!=revision)throw WorkflowExecutionStore.conflict();
        var execution=store.executableDefinition(d);long datasource=FixedJson.number(execution,"datasourceId",1);
        authorization.requireDatasource(datasource,"query");authorization.requireDatasource(datasource,"write");
        client.requireWritePolicy(FixedJson.text(execution,"sql").replace("${bizDate}","2000-01-01"));
        var saved=store.approve(d,actor);scheduler.sync(tasks.getById(id));return state(saved);
    }
    @PostMapping("/cron-binding") public State bind(@PathVariable long id,@RequestBody byte[] raw){
        var actor=WorkflowActor.manual(authorization.currentUser());var body=FixedJson.parse(raw,65536);FixedJson.fields(body,"expectedRevision","expectedGeneration");
        authorization.require("workflow_task",id,"execute");var saved=store.bindCron(id,FixedJson.number(body,"expectedRevision",1),FixedJson.number(body,"expectedGeneration",1),actor);
        scheduler.sync(tasks.getById(id));return state(saved);
    }
}
