package com.aetheris.quality.service.impl;

import com.aetheris.quality.entity.QualityRule;
import com.aetheris.quality.mapper.QualityRuleMapper;
import com.aetheris.quality.service.QualityRuleService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class QualityRuleServiceImpl extends ServiceImpl<QualityRuleMapper, QualityRule>
        implements QualityRuleService {
}
