package com.aetheris.metadata.service.impl;

import com.aetheris.metadata.entity.MetadataTable;
import com.aetheris.metadata.mapper.MetadataTableMapper;
import com.aetheris.metadata.service.MetadataTableService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class MetadataTableServiceImpl extends ServiceImpl<MetadataTableMapper, MetadataTable>
        implements MetadataTableService {

    @Override
    public List<MetadataTable> search(String keyword, int limit) {
        if (!StringUtils.hasText(keyword)) {
            return List.of();
        }
        String pattern = "%" + keyword.trim() + "%";
        // 已经消失的表（采集时物理表不存在，标记为 stale）对搜索用户没有价值，排除掉。
        return list(new LambdaQueryWrapper<MetadataTable>()
                .ne(MetadataTable::getStatus, "stale")
                .and(w -> w
                        .like(MetadataTable::getTableName, pattern)
                        .or().like(MetadataTable::getTableComment, pattern)
                        .or().like(MetadataTable::getDomain, pattern))
                .last("LIMIT " + Math.max(1, limit)));
    }
}
