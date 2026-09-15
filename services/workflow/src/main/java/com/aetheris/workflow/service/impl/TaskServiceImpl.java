package com.aetheris.workflow.service.impl;

import com.aetheris.workflow.entity.Task;
import com.aetheris.workflow.mapper.TaskMapper;
import com.aetheris.workflow.service.TaskService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskServiceImpl extends ServiceImpl<TaskMapper, Task>
        implements TaskService {
    @Override
    @Transactional
    public boolean save(Task entity) { return super.save(entity); }

    @Override
    @Transactional
    public boolean updateById(Task entity) { return super.updateById(entity); }

    @Override
    @Transactional
    public boolean removeById(java.io.Serializable id) { return super.removeById(id); }
}
