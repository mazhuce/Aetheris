package com.aetheris.quality.service.impl;

import com.aetheris.quality.entity.QualityQuarantineRecord;
import com.aetheris.quality.mapper.QualityQuarantineRecordMapper;
import com.aetheris.quality.service.QualityQuarantineService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class QualityQuarantineServiceImpl extends ServiceImpl<QualityQuarantineRecordMapper, QualityQuarantineRecord>
        implements QualityQuarantineService {
}
