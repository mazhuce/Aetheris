-- Engine attempts use their original engine receipt/result, not a Metadata execution receipt.
-- Keep the existing SQL/quality and waiver requirements; a status alone never opens an edge.
CREATE OR REPLACE FUNCTION workflow.pipeline_success_evidence(node_key BIGINT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
  SELECT EXISTS(SELECT 1 FROM workflow.pipeline_node_run node
    JOIN workflow.task_instance instance ON instance.id=node.instance_id
    JOIN workflow.execution_attempt attempt ON attempt.id=node.attempt_id AND attempt.instance_id=instance.id
    JOIN workflow.pipeline_run run ON run.id=node.pipeline_run_id
    WHERE node.id=node_key AND node.status IN ('succeeded','waived') AND node.execution_revision IS NOT NULL
      AND instance.active_attempt_id=attempt.id AND instance.fencing_token=node.fencing_token
      AND attempt.fencing_token=node.fencing_token AND (
        (node.status='succeeded' AND instance.status='success' AND attempt.status='succeeded')
        OR (node.status='waived' AND instance.status='failed' AND attempt.status='failed' AND attempt.task_type='quality'
          AND attempt.error_code='QUALITY_GATE_FAILED' AND attempt.quality_result_id IS NOT NULL
          AND attempt.quality_manifest_sha256 IS NOT NULL AND attempt.quality_evidence_json->>'state'='failed'
          AND (attempt.quality_evidence_json->>'violationCount')::bigint>(attempt.quality_evidence_json->>'thresholdValue')::bigint
          AND EXISTS(SELECT 1 FROM workflow.quality_waiver waiver WHERE waiver.id=node.waiver_id
            AND waiver.quality_task_id=node.task_id AND waiver.biz_date=instance.biz_date AND waiver.expires_at>clock_timestamp())))
      AND attempt.finished_at IS NOT NULL AND instance.finished_at IS NOT NULL
      AND attempt.pipeline_node_id=node.id AND attempt.pipeline_run_id=run.id
      AND attempt.invocation_json=run.invocation_json AND attempt.execution_revision=node.execution_revision
      AND attempt.approval_json=node.approval_json AND attempt.manifest_sealed_at IS NOT NULL
      AND (
        (attempt.task_type IN ('sql','quality') AND attempt.metadata_receipt_id IS NOT NULL)
        OR (attempt.task_type IN ('spark','flink') AND node.status='succeeded'
          AND attempt.engine_dispatch_token IS NOT NULL
          AND attempt.manifest_sha256=encode(sha256(convert_to(attempt.sealed_manifest::text,'UTF8')),'hex')
          AND attempt.engine_receipt_sha256=encode(sha256(convert_to(attempt.engine_receipt_json::text,'UTF8')),'hex')
          AND attempt.engine_result_sha256=encode(sha256(convert_to(attempt.engine_result_json::text,'UTF8')),'hex')
          AND attempt.engine_receipt_json->>'schemaVersion'='1'
          AND attempt.engine_receipt_json->>'engine'=attempt.task_type
          AND attempt.engine_receipt_json->>'instanceId'=instance.id::text
          AND attempt.engine_receipt_json->>'attemptId'=attempt.id::text
          AND attempt.engine_receipt_json->>'fence'=attempt.fencing_token::text
          AND attempt.engine_receipt_json->>'manifestSha256'=attempt.manifest_sha256
          AND attempt.engine_receipt_json->>'engineDefinitionSha256'=encode(sha256(convert_to((attempt.sealed_manifest->'execution'->'engineDefinition')::text,'UTF8')),'hex')
          AND attempt.engine_receipt_json->>'origin'=attempt.engine_runner_origin
          AND attempt.engine_receipt_json->>'requestKey'='attempt-'||attempt.id||'-f'||attempt.fencing_token
          AND attempt.engine_receipt_json->>'remoteHandle'=attempt.remote_handle
          AND attempt.engine_result_json->>'schemaVersion'='1'
          AND attempt.engine_result_json->>'engine'=attempt.task_type
          AND attempt.engine_result_json->>'instanceId'=instance.id::text
          AND attempt.engine_result_json->>'attemptId'=attempt.id::text
          AND attempt.engine_result_json->>'fence'=attempt.fencing_token::text
          AND attempt.engine_result_json->>'manifestSha256'=attempt.manifest_sha256
          AND attempt.engine_result_json->>'receiptSha256'=attempt.engine_receipt_sha256
          AND attempt.engine_result_json->>'state'='succeeded'
          AND attempt.engine_result_json->'remote'->>'id'=attempt.remote_handle
          AND attempt.engine_result_json->'remote'->>'status'='succeeded')))
$$;
