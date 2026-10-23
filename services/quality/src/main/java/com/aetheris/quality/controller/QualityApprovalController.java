package com.aetheris.quality.controller;

import com.aetheris.quality.fixedexecution.*;
import com.aetheris.quality.security.ResourceAuthorization;
import com.aetheris.quality.service.*;
import com.aetheris.security.servlet.TrustedUser;
import org.springframework.web.bind.annotation.*;

/** Approval and cron binding are explicit typed-identity operations, never task Entity input. */
@RestController
@RequestMapping("/api/quality/rules/{id}")
public class QualityApprovalController {
    private final QualityExecutionStore store;private final ResourceAuthorization authorization;
    private final QualitySchedulerService scheduler;private final QualityRuleService tasks;
    public QualityApprovalController(QualityExecutionStore store,ResourceAuthorization authorization,QualitySchedulerService scheduler,QualityRuleService tasks){
        this.store=store;this.authorization=authorization;this.scheduler=scheduler;this.tasks=tasks;
    }
    public record State(long executionRevision,String definitionSha256,boolean approved,long bindingGeneration,long scheduleGeneration,boolean cronBound) { }
    private State state(QualityExecutionStore.Definition d){return new State(d.revision(),d.definitionSha256(),d.approved(),d.bindingGeneration(),d.scheduleGeneration(),d.cronBound());}
    private QualityActor actor(TrustedUser user){if(user==null||!authorization.currentUser().compactProof().equals(user.compactProof()))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);return QualityActor.manual(user);}
    @GetMapping("/approval") public State get(@PathVariable long id){authorization.require("quality_rule",id,"view");return state(store.definition(id));}
    @PostMapping("/approve") public State approve(@PathVariable long id,@RequestAttribute("com.aetheris.security.servlet.TrustedUser.authenticated") TrustedUser user,@RequestBody byte[] raw) {
        var actor=actor(user);var body=FixedJson.parse(raw,65536);FixedJson.fields(body,"expectedRevision");long revision=FixedJson.number(body,"expectedRevision",1);
        authorization.require("quality_rule",id,"update");var definition=store.definition(id);
        if(definition.revision()!=revision)throw QualityExecutionStore.conflict();var execution=store.executableDefinition(definition);
        authorization.requireDatasourceQuery(FixedJson.number(execution,"datasourceId",1));
        var saved=store.approve(definition,actor);scheduler.sync(tasks.getById(id));return state(saved);
    }
    @PostMapping("/cron-binding") public State bind(@PathVariable long id,@RequestAttribute("com.aetheris.security.servlet.TrustedUser.authenticated") TrustedUser user,@RequestBody byte[] raw) {
        var actor=actor(user);var body=FixedJson.parse(raw,65536);FixedJson.fields(body,"expectedRevision","expectedGeneration");
        long revision=FixedJson.number(body,"expectedRevision",1),generation=FixedJson.number(body,"expectedGeneration",1);
        authorization.require("quality_rule",id,"execute");
        var saved=store.bindCron(id,revision,generation,actor);scheduler.sync(tasks.getById(id));return state(saved);
    }
    @ExceptionHandler(IllegalStateException.class) org.springframework.http.ResponseEntity<?> conflict(){return org.springframework.http.ResponseEntity.status(409).body(java.util.Map.of("message","执行或调度版本已变化，请重新确认"));}
}
