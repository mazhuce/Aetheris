package com.aetheris.system.service.impl;

import com.aetheris.system.entity.PermissionEvent;
import com.aetheris.system.mapper.PermissionEventMapper;
import com.aetheris.system.service.PermissionEventService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class PermissionEventServiceImpl extends ServiceImpl<PermissionEventMapper, PermissionEvent>
        implements PermissionEventService {
}