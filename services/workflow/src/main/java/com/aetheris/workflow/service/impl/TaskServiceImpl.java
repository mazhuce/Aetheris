package com.aetheris.workflow.service.impl;

import com.aetheris.workflow.entity.Task;
import com.aetheris.workflow.mapper.TaskMapper;
import com.aetheris.workflow.service.TaskService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class TaskServiceImpl extends ServiceImpl<TaskMapper, Task>
        implements TaskService {
}
