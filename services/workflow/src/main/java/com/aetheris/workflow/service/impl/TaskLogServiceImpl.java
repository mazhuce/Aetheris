package com.aetheris.workflow.service.impl;

import com.aetheris.workflow.entity.TaskLog;
import com.aetheris.workflow.mapper.TaskLogMapper;
import com.aetheris.workflow.service.TaskLogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class TaskLogServiceImpl extends ServiceImpl<TaskLogMapper, TaskLog>
        implements TaskLogService {
}
