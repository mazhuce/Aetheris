package com.aetheris.workflow.service;

import com.aetheris.workflow.entity.ReleaseRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Freezes the reviewed release definition and enforces quality gates at publish time. */
@Service
public class ReleaseGovernanceService {
    private final ReleaseRecordService records;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final QualityExecutionClient quality;
    private final MetadataImpactClient impact;

    public ReleaseGovernanceService(ReleaseRecordService records, JdbcTemplate jdbc,
                                    ObjectMapper mapper, QualityExecutionClient quality,
                                    MetadataImpactClient impact) {
        this.records = records; this.jdbc = jdbc; this.mapper = mapper; this.quality = quality;
        this.impact = impact;
    }

    @Transactional
    public ReleaseRecord create(ReleaseRecord record, String username) {
        record.setId(null); record.setStatus("pending"); record.setRequestedBy(username);
        record.setApprovedBy(null); record.setReleasedAt(null);
        records.save(record);
        refreshSnapshot(record);
        return records.getById(record.getId());
    }

    @Transactional
    public ReleaseRecord update(long id, ReleaseRecord input) {
        ReleaseRecord existing = required(id);
        if (!"pending".equals(existing.getStatus())) {
            throw new IllegalStateException("审批后定义已冻结；如需修改，请新建发布单");
        }
        existing.setTitle(input.getTitle()); existing.setReleaseType(input.getReleaseType());
        existing.setTargetName(input.getTargetName()); existing.setTargetVersion(input.getTargetVersion());
        existing.setTargetRefId(input.getTargetRefId()); existing.setChangeSummary(input.getChangeSummary());
        existing.setQualityRuleId(input.getQualityRuleId()); existing.setUpdatedAt(OffsetDateTime.now());
        records.updateById(existing);
        refreshSnapshot(existing);
        return records.getById(id);
    }

    @Transactional
    public ReleaseRecord approve(long id, String username) {
        ReleaseRecord record = required(id);
        Map<String, Object> impactSummary = "table".equals(record.getReleaseType())
                ? impact.impact(record.getTargetName()) : Map.of("status", "not_applicable");
        int changed = jdbc.update("""
                UPDATE workflow.release_record
                SET status='approved',approved_by=?,approved_at=now(),approved_digest=snapshot_digest,
                    impact_summary=?::jsonb,updated_at=now()
                WHERE id=? AND status='pending' AND snapshot_digest IS NOT NULL
                """, username, json(impactSummary), id);
        if (changed != 1) throw new IllegalStateException("发布单状态已变化或快照尚未生成");
        return records.getById(id);
    }

    @Transactional
    public ReleaseRecord publish(long id) {
        ReleaseRecord record = required(id);
        if (!"approved".equals(record.getStatus())) throw new IllegalStateException("发布单尚未审批");
        if (record.getApprovedDigest() == null || !record.getApprovedDigest().equals(record.getSnapshotDigest())) {
            throw new IllegalStateException("发布定义与审批快照不一致，禁止发布");
        }
        Long qualityResultId = null;
        if (record.getQualityRuleId() != null) {
            QualityExecutionClient.QualityResult result = quality.latest(record.getQualityRuleId());
            if (result == null || !"passed".equals(result.status()) || result.finishedAt() == null
                    || record.getApprovedAt() == null || result.finishedAt().isBefore(record.getApprovedAt())) {
                throw new IllegalStateException("质量门禁须在审批后重新检查并通过");
            }
            qualityResultId = result.id();
        }
        int changed = jdbc.update("""
                UPDATE workflow.release_record
                SET status='released',released_at=now(),quality_result_id=?,updated_at=now()
                WHERE id=? AND status='approved' AND snapshot_digest=approved_digest
                """, qualityResultId, id);
        if (changed != 1) throw new IllegalStateException("发布状态或审批快照已变化");
        return records.getById(id);
    }

    @Transactional
    public void delete(long id) {
        int changed = jdbc.update("DELETE FROM workflow.release_record WHERE id=? AND status IN ('pending','rejected')", id);
        if (changed != 1) throw new IllegalStateException("只有待审批或已拒绝发布单可以删除");
    }

    private void refreshSnapshot(ReleaseRecord record) {
        Map<String, Object> snapshot = new TreeMap<>();
        snapshot.put("releaseNo", record.getReleaseNo()); snapshot.put("title", record.getTitle());
        snapshot.put("releaseType", record.getReleaseType()); snapshot.put("targetName", record.getTargetName());
        snapshot.put("targetVersion", record.getTargetVersion()); snapshot.put("targetRefId", record.getTargetRefId());
        snapshot.put("changeSummary", record.getChangeSummary()); snapshot.put("qualityRuleId", record.getQualityRuleId());
        try {
            String json = json(snapshot);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8)));
            jdbc.update("""
                    UPDATE workflow.release_record
                    SET snapshot_json=?::jsonb,snapshot_digest=?,approved_digest=NULL,
                        approved_by=NULL,approved_at=NULL,updated_at=now() WHERE id=? AND status='pending'
                    """, json, digest, record.getId());
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("发布数据无法序列化", error); }
    }

    private ReleaseRecord required(long id) {
        ReleaseRecord record = records.getById(id);
        if (record == null) throw new IllegalArgumentException("发布单不存在");
        return record;
    }
}
