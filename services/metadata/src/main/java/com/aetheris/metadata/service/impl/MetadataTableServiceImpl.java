package com.aetheris.metadata.service.impl;

import com.aetheris.metadata.entity.MetadataTable;
import com.aetheris.metadata.mapper.MetadataTableMapper;
import com.aetheris.metadata.service.MetadataTableService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class MetadataTableServiceImpl extends ServiceImpl<MetadataTableMapper, MetadataTable>
        implements MetadataTableService {
}
