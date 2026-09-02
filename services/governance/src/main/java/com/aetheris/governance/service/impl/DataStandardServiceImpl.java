package com.aetheris.governance.service.impl;

import com.aetheris.governance.entity.DataStandard;
import com.aetheris.governance.mapper.DataStandardMapper;
import com.aetheris.governance.service.DataStandardService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class DataStandardServiceImpl extends ServiceImpl<DataStandardMapper, DataStandard>
        implements DataStandardService {
}
