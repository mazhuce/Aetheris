package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.LogicalModel;
import com.aetheris.metadata.mapper.LogicalModelMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;

/** 逻辑模型服务：模型检索与实体关系维护，编码唯一。 */
@Service
public class LogicalModelService {

    private final LogicalModelMapper mapper;

    public LogicalModelService(LogicalModelMapper mapper) {
        this.mapper = mapper;
    }

    /** 列表，可选 keyword 同时模糊匹配模型名称与编码。 */
    public List<LogicalModel> list(String keyword) {
        LambdaQueryWrapper<LogicalModel> wrapper = new LambdaQueryWrapper<LogicalModel>()
                .orderByDesc(LogicalModel::getId);
        if (StringUtils.hasText(keyword)) {
            String trimmed = keyword.trim();
            wrapper.and(w -> w.like(LogicalModel::getModelName, trimmed)
                    .or().like(LogicalModel::getModelCode, trimmed));
        }
        return mapper.selectList(wrapper);
    }

    public LogicalModel getById(Long id) {
        return mapper.selectById(id);
    }

    /** 编码是否已被占用；excludeId 用于更新时排除自身。 */
    public boolean codeExists(String code, Long excludeId) {
        return mapper.selectCount(new LambdaQueryWrapper<LogicalModel>()
                .eq(LogicalModel::getModelCode, code)
                .ne(excludeId != null, LogicalModel::getId, excludeId)) > 0;
    }

    public LogicalModel create(LogicalModel model) {
        mapper.insert(model);
        return model;
    }

    public boolean update(LogicalModel model) {
        model.setUpdatedAt(OffsetDateTime.now());
        return mapper.updateById(model) > 0;
    }

    public boolean remove(Long id) {
        return mapper.deleteById(id) > 0;
    }
}
