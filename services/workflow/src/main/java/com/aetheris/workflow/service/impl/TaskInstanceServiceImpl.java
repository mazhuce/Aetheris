package com.aetheris.workflow.service.impl;

import com.aetheris.workflow.entity.TaskInstance;
import com.aetheris.workflow.mapper.TaskInstanceMapper;
import com.aetheris.workflow.service.TaskInstanceService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class TaskInstanceServiceImpl extends ServiceImpl<TaskInstanceMapper, TaskInstance>
        implements TaskInstanceService {
}
