package com.aetheris.workflow.service.impl;

import com.aetheris.workflow.entity.ReleaseRecord;
import com.aetheris.workflow.mapper.ReleaseRecordMapper;
import com.aetheris.workflow.service.ReleaseRecordService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class ReleaseRecordServiceImpl extends ServiceImpl<ReleaseRecordMapper, ReleaseRecord>
        implements ReleaseRecordService {
}
