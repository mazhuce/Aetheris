package com.aetheris.ingestion.controller;

import com.aetheris.ingestion.fixedexecution.*;
import com.aetheris.ingestion.security.ResourceAuthorization;
import com.aetheris.ingestion.service.*;
import com.aetheris.security.servlet.TrustedUser;
import org.springframework.web.bind.annotation.*;

/** Approval and cron binding are explicit typed-identity operations, never task Entity input. */
@RestController
@RequestMapping("/api/ingestion/tasks/{id}")
public class IngestionApprovalController {
    private final IngestionExecutionStore store;private final ResourceAuthorization authorization;
    private final IngestionSchedulerService scheduler;private final IngestionTaskService tasks;
    public IngestionApprovalController(IngestionExecutionStore store,ResourceAuthorization authorization,IngestionSchedulerService scheduler,IngestionTaskService tasks){
        this.store=store;this.authorization=authorization;this.scheduler=scheduler;this.tasks=tasks;
    }
    public record State(long executionRevision,String definitionSha256,boolean approved,long bindingGeneration,long scheduleGeneration,boolean cronBound) { }
    private State state(IngestionExecutionStore.Definition d){return new State(d.revision(),d.definitionSha256(),d.approved(),d.bindingGeneration(),d.scheduleGeneration(),d.cronBound());}
    private IngestionActor actor(TrustedUser user){if(user==null||!authorization.currentUser().compactProof().equals(user.compactProof()))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);return IngestionActor.manual(user);}
    @GetMapping("/approval") public State get(@PathVariable long id){authorization.require("ingestion_task",id,"view");return state(store.definition(id));}
    @PostMapping("/approve") public State approve(@PathVariable long id,@RequestAttribute("com.aetheris.security.servlet.TrustedUser.authenticated") TrustedUser user,@RequestBody byte[] raw) {
        var actor=actor(user);var body=FixedJson.parse(raw,65536);FixedJson.fields(body,"expectedRevision");long revision=FixedJson.number(body,"expectedRevision",1);
        authorization.require("ingestion_task",id,"update");var definition=store.definition(id);
        if(definition.revision()!=revision)throw IngestionExecutionStore.conflict();var execution=store.executableDefinition(definition);
        authorization.requireDatasource(FixedJson.number(execution,"sourceDatasourceId",1),"query");
        authorization.requireDatasource(FixedJson.number(execution,"targetDatasourceId",1),"write");
        var saved=store.approve(definition,actor);scheduler.sync(tasks.getById(id));return state(saved);
    }
    @PostMapping("/cron-binding") public State bind(@PathVariable long id,@RequestAttribute("com.aetheris.security.servlet.TrustedUser.authenticated") TrustedUser user,@RequestBody byte[] raw) {
        var actor=actor(user);var body=FixedJson.parse(raw,65536);FixedJson.fields(body,"expectedRevision","expectedGeneration");
        long revision=FixedJson.number(body,"expectedRevision",1),generation=FixedJson.number(body,"expectedGeneration",1);
        authorization.require("ingestion_task",id,"execute");
        var saved=store.bindCron(id,revision,generation,actor);scheduler.sync(tasks.getById(id));return state(saved);
    }
    @ExceptionHandler(IllegalStateException.class) org.springframework.http.ResponseEntity<?> conflict(){return org.springframework.http.ResponseEntity.status(409).body(java.util.Map.of("message","执行或调度版本已变化，请重新确认"));}
}
