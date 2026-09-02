package com.aetheris.quality.service.impl;

import com.aetheris.quality.entity.QualityCheckResult;
import com.aetheris.quality.mapper.QualityCheckResultMapper;
import com.aetheris.quality.service.QualityCheckResultService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class QualityCheckResultServiceImpl extends ServiceImpl<QualityCheckResultMapper, QualityCheckResult>
        implements QualityCheckResultService {
}
