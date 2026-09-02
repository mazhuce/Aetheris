package com.aetheris.system.service.impl;

import com.aetheris.system.entity.Notification;
import com.aetheris.system.mapper.NotificationMapper;
import com.aetheris.system.service.NotificationService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class NotificationServiceImpl extends ServiceImpl<NotificationMapper, Notification>
        implements NotificationService {
}
