package com.aetheris.system.service.impl;

import com.aetheris.system.entity.AuditLog;
import com.aetheris.system.mapper.AuditLogMapper;
import com.aetheris.system.service.AuditLogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class AuditLogServiceImpl extends ServiceImpl<AuditLogMapper, AuditLog>
        implements AuditLogService {
}
