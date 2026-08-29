package com.aetheris.workflow.service.impl;

import com.aetheris.workflow.entity.TaskDependency;
import com.aetheris.workflow.mapper.TaskDependencyMapper;
import com.aetheris.workflow.service.TaskDependencyService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class TaskDependencyServiceImpl extends ServiceImpl<TaskDependencyMapper, TaskDependency>
        implements TaskDependencyService {
}
