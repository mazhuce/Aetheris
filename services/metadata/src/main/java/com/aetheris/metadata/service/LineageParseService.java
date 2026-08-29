package com.aetheris.metadata.service;

import com.aetheris.metadata.dto.LineageParseResult;
import com.aetheris.metadata.entity.LineageRelation;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把 SQL 解析出的表级血缘写入 lineage_relation。
 *
 * <p>幂等策略：对同一目标表重新解析时，先删掉该表既有的 parsed 入边再写入新的，
 * 这样 SQL 改写后不会残留已经不存在的上游依赖。人工登记（source=manual）的边不受影响，
 * 与元数据采集区分 manual/auto 的取舍逻辑一致。
 */
@Service
public class LineageParseService {

    private static final Logger log = LoggerFactory.getLogger(LineageParseService.class);
    private static final String SOURCE_PARSED = "parsed";
    private static final String SOURCE_MANUAL = "manual";

    private final LineageRelationService lineageRelationService;

    public LineageParseService(LineageRelationService lineageRelationService) {
        this.lineageRelationService = lineageRelationService;
    }

    /**
     * 解析 SQL 并写入血缘。
     *
     * @param sql         一段或多条 SQL，可含注释
     * @param relationType 关系类型标签，例如 etl；为空时按 etl 记录
     */
    @Transactional
    public LineageParseResult parseAndSave(String sql, String relationType) {
        List<SqlLineageExtractor.TableLineage> lineages = SqlLineageExtractor.extract(sql);
        if (lineages.isEmpty()) {
            return new LineageParseResult(0, 0, 0, 0, List.of());
        }

        String type = StringUtils.hasText(relationType) ? relationType.trim() : "etl";
        // 已清理过旧边的目标表。同一目标表在一段脚本里可能被多条语句写入，
        // 只在首次遇到时清理，否则后一条语句会把前一条刚写入的边删掉。
        Set<String> clearedTargets = new LinkedHashSet<>();
        List<String> edges = new ArrayList<>();
        int created = 0;
        int replaced = 0;
        int skipped = 0;

        for (SqlLineageExtractor.TableLineage lineage : lineages) {
            if (clearedTargets.add(lineage.targetTable())) {
                replaced += clearParsedEdges(lineage.targetTable());
            }

            for (String source : lineage.sourceTables()) {
                if (hasManualEdge(source, lineage.targetTable())) {
                    // 人工登记优先：已有人工维护的同名边时不再写入解析结果。
                    skipped++;
                    continue;
                }
                LineageRelation relation = new LineageRelation();
                relation.setSourceTable(source);
                relation.setTargetTable(lineage.targetTable());
                relation.setRelationType(type);
                relation.setSource(SOURCE_PARSED);
                relation.setDescription("由 SQL 解析生成");
                lineageRelationService.save(relation);
                created++;
                edges.add(source + " → " + lineage.targetTable());
            }
        }

        log.info("Lineage parsed: {} edges created for {} target tables", created, clearedTargets.size());
        return new LineageParseResult(lineages.size(), created, replaced, skipped, edges);
    }

    /** 删除该目标表既有的解析边，返回删除数量。 */
    private int clearParsedEdges(String targetTable) {
        return (int) lineageRelationService.getBaseMapper().delete(
                new LambdaQueryWrapper<LineageRelation>()
                        .eq(LineageRelation::getTargetTable, targetTable)
                        .eq(LineageRelation::getSource, SOURCE_PARSED));
    }

    private boolean hasManualEdge(String sourceTable, String targetTable) {
        return lineageRelationService.count(
                new LambdaQueryWrapper<LineageRelation>()
                        .eq(LineageRelation::getSourceTable, sourceTable)
                        .eq(LineageRelation::getTargetTable, targetTable)
                        .eq(LineageRelation::getSource, SOURCE_MANUAL)) > 0;
    }
}
