package com.aetheris.metadata.service.impl;

import com.aetheris.metadata.entity.LineageRelation;
import com.aetheris.metadata.mapper.LineageRelationMapper;
import com.aetheris.metadata.service.LineageRelationService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class LineageRelationServiceImpl extends ServiceImpl<LineageRelationMapper, LineageRelation>
        implements LineageRelationService {
}
