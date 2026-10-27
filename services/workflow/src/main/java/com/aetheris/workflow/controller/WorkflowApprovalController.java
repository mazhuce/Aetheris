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
    private WorkflowQualityClient quality;
    @org.springframework.beans.factory.annotation.Autowired public void installQuality(WorkflowQualityClient quality){this.quality=java.util.Objects.requireNonNull(quality);}
    public WorkflowApprovalController(WorkflowExecutionStore store,ResourceAuthorization authorization,SqlExecutionClient client,TaskSchedulerService scheduler,TaskService tasks){this.store=store;this.authorization=authorization;this.client=client;this.scheduler=scheduler;this.tasks=tasks;}
    public record State(long executionRevision,String definitionSha256,boolean approved,long bindingGeneration,long scheduleGeneration,boolean cronBound){}
    private State state(WorkflowExecutionStore.Definition d){return new State(d.revision(),d.definitionSha256(),d.approved(),d.bindingGeneration(),d.scheduleGeneration(),d.cronBound());}
    @GetMapping("/approval") public State get(@PathVariable long id){authorization.require("workflow_task",id,"view");return state(store.definition(id));}
    @PostMapping("/approve") public State approve(@PathVariable long id,@RequestBody byte[] raw){
        var actor=WorkflowActor.manual(authorization.currentUser());var body=FixedJson.parse(raw,65536);FixedJson.fields(body,"expectedRevision");long revision=FixedJson.number(body,"expectedRevision",1);
        authorization.require("workflow_task",id,"update");var d=store.definition(id);if(d.revision()!=revision)throw WorkflowExecutionStore.conflict();
        var rawDefinition=FixedJson.parse(d.canonicalDefinition());
        if(java.util.Set.of("spark","flink").contains(rawDefinition.path("taskType").asText())){
            authorization.requireManage();var saved=store.approveEngine(d,actor);scheduler.sync(tasks.getById(id));return state(saved);
        }
        if("quality".equals(rawDefinition.path("taskType").asText())){
            long rule=FixedJson.number(rawDefinition,"qualityRuleId",1);authorization.requireQualityExecute(rule);
            if(quality==null)throw new IllegalStateException("质量批准客户端未配置");var child=quality.approvedDefinition(rule);
            var saved=store.approveQuality(d,actor,child.executionRevision(),child.definitionSha256());scheduler.sync(tasks.getById(id));return state(saved);
        }
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
