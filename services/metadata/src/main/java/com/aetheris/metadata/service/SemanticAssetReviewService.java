package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.SemanticAssetReview;
import com.aetheris.metadata.mapper.SemanticAssetReviewMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class SemanticAssetReviewService {
    private static final List<String> TYPES = List.of("logical_model", "semantic_dimension", "metric_definition");
    private final SemanticAssetReviewMapper mapper;
    private final JdbcTemplate jdbc;
    public SemanticAssetReviewService(SemanticAssetReviewMapper mapper, JdbcTemplate jdbc) { this.mapper = mapper; this.jdbc = jdbc; }

    public List<SemanticAssetReview> list(String type, String status) {
        String normalized = normalizeType(type, true);
        return mapper.selectList(new LambdaQueryWrapper<SemanticAssetReview>()
                .eq(normalized != null, SemanticAssetReview::getAssetType, normalized)
                .eq(StringUtils.hasText(status), SemanticAssetReview::getStatus, status.trim().toLowerCase(Locale.ROOT))
                .orderByDesc(SemanticAssetReview::getRequestedAt));
    }

    @Transactional
    public SemanticAssetReview submit(String type, Long assetId, String username, String note,
                                      OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        String assetType = normalizeType(type, false);
        if (assetId == null) throw new IllegalArgumentException("资产 ID 不能为空");
        if (!StringUtils.hasText(note) || note.trim().length() < 5) throw new IllegalArgumentException("变更说明至少需要 5 个字符");
        AssetState asset = assetState(assetType, assetId);
        if (asset == null) throw new IllegalArgumentException("语义资产不存在");
        if (!"draft".equals(asset.status())) throw new IllegalStateException("只有草稿版本可以提交评审");
        if (windowStart != null && windowEnd != null && !windowEnd.isAfter(windowStart)) throw new IllegalArgumentException("发布窗口结束时间必须晚于开始时间");
        if (mapper.selectCount(new LambdaQueryWrapper<SemanticAssetReview>().eq(SemanticAssetReview::getAssetType, assetType).eq(SemanticAssetReview::getAssetId, assetId).eq(SemanticAssetReview::getVersionNo, asset.version()).eq(SemanticAssetReview::getStatus, "pending")) > 0) throw new IllegalStateException("该版本已有待评审申请");
        SemanticAssetReview review = new SemanticAssetReview(); review.setAssetType(assetType); review.setAssetId(assetId); review.setVersionNo(asset.version()); review.setStatus("pending"); review.setRequestedBy(username); review.setChangeNote(note.trim()); review.setPublishWindowStart(windowStart); review.setPublishWindowEnd(windowEnd); mapper.insert(review); return review;
    }

    @Transactional public SemanticAssetReview decide(Long id, String username, boolean approve, String comment) {
        SemanticAssetReview review = require(id);
        if (!"pending".equals(review.getStatus())) throw new IllegalStateException("只有待评审申请可以审批");
        if (username.equals(review.getRequestedBy())) throw new IllegalStateException("提交人不能审批自己的变更");
        if (!StringUtils.hasText(comment)) throw new IllegalArgumentException("审批意见不能为空");
        review.setStatus(approve ? "approved" : "rejected"); review.setReviewedBy(username); review.setReviewComment(comment.trim()); review.setReviewedAt(OffsetDateTime.now()); mapper.updateById(review); return review;
    }

    public SemanticAssetReview requireApproved(String type, Long assetId, Integer version) {
        String assetType = normalizeType(type, false); SemanticAssetReview review = mapper.selectOne(new LambdaQueryWrapper<SemanticAssetReview>().eq(SemanticAssetReview::getAssetType, assetType).eq(SemanticAssetReview::getAssetId, assetId).eq(SemanticAssetReview::getVersionNo, version).eq(SemanticAssetReview::getStatus, "approved").orderByDesc(SemanticAssetReview::getReviewedAt).last("LIMIT 1"));
        if (review == null) throw new IllegalStateException("当前版本尚未通过审批");
        OffsetDateTime now = OffsetDateTime.now(); if (review.getPublishWindowStart() != null && now.isBefore(review.getPublishWindowStart())) throw new IllegalStateException("尚未到达发布窗口"); if (review.getPublishWindowEnd() != null && now.isAfter(review.getPublishWindowEnd())) throw new IllegalStateException("发布窗口已结束"); return review;
    }

    @Transactional public void markPublished(Long id) { SemanticAssetReview review = require(id); review.setStatus("published"); review.setPublishedAt(OffsetDateTime.now()); mapper.updateById(review); }
    public SemanticAssetReview require(Long id) { SemanticAssetReview review = mapper.selectById(id); if (review == null) throw new IllegalArgumentException("评审申请不存在"); return review; }
    private String normalizeType(String type, boolean nullable) { if (!StringUtils.hasText(type)) { if (nullable) return null; throw new IllegalArgumentException("资产类型不能为空"); } String value = type.trim().toLowerCase(Locale.ROOT); if (!TYPES.contains(value)) throw new IllegalArgumentException("不支持的语义资产类型"); return value; }
    private AssetState assetState(String type, Long id) { String table = switch (type) { case "logical_model" -> "logical_model"; case "semantic_dimension" -> "semantic_dimension"; default -> "metric_definition"; }; return jdbc.query("SELECT status, version FROM metadata." + table + " WHERE id = ?", rs -> rs.next() ? new AssetState(rs.getString(1), rs.getInt(2)) : null, id); }
    private record AssetState(String status, Integer version) {}
}
