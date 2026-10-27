package com.aetheris.workflow.fixedexecution;

import com.aetheris.workflow.entity.TaskInstance;
import org.springframework.stereotype.Service;
@Service
public final class WorkflowEngineFacade {
    private final WorkflowSparkExecutor spark;private final WorkflowFlinkExecutor flink;private final WorkflowEngineAuthority authority;
    public WorkflowEngineFacade(WorkflowSparkExecutor spark,WorkflowFlinkExecutor flink,WorkflowEngineAuthority authority){this.spark=spark;this.flink=flink;this.authority=authority;}
    public TaskInstance execute(WorkflowExecutionStore.Claim claim){return switch(authority.original(claim).engine()){case "spark"->spark.execute(claim);case "flink"->flink.execute(claim);default->throw FixedJson.invalid();};}
}
