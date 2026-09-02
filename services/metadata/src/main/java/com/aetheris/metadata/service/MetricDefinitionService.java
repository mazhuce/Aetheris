package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.MetricDefinition;
import com.aetheris.metadata.mapper.MetricDefinitionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;

/** 指标定义服务：指标的检索、登记与口径维护，编码唯一。 */
@Service
public class MetricDefinitionService {

    private final MetricDefinitionMapper mapper;

    public MetricDefinitionService(MetricDefinitionMapper mapper) {
        this.mapper = mapper;
    }

    /** 列表，可选 keyword 同时模糊匹配指标名称与编码。 */
    public List<MetricDefinition> list(String keyword) {
        LambdaQueryWrapper<MetricDefinition> wrapper = new LambdaQueryWrapper<MetricDefinition>()
                .orderByDesc(MetricDefinition::getId);
        if (StringUtils.hasText(keyword)) {
            String trimmed = keyword.trim();
            wrapper.and(w -> w.like(MetricDefinition::getMetricName, trimmed)
                    .or().like(MetricDefinition::getMetricCode, trimmed));
        }
        return mapper.selectList(wrapper);
    }

    public MetricDefinition getById(Long id) {
        return mapper.selectById(id);
    }

    /** 编码是否已被占用；excludeId 用于更新时排除自身。 */
    public boolean codeExists(String code, Long excludeId) {
        return mapper.selectCount(new LambdaQueryWrapper<MetricDefinition>()
                .eq(MetricDefinition::getMetricCode, code)
                .ne(excludeId != null, MetricDefinition::getId, excludeId)) > 0;
    }

    public MetricDefinition create(MetricDefinition metric) {
        mapper.insert(metric);
        return metric;
    }

    public boolean update(MetricDefinition metric) {
        metric.setUpdatedAt(OffsetDateTime.now());
        return mapper.updateById(metric) > 0;
    }

    public boolean remove(Long id) {
        return mapper.deleteById(id) > 0;
    }
}
