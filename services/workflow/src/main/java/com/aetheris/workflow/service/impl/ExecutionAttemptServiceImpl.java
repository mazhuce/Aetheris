package com.aetheris.workflow.service.impl;

import com.aetheris.workflow.entity.ExecutionAttempt;
import com.aetheris.workflow.mapper.ExecutionAttemptMapper;
import com.aetheris.workflow.service.ExecutionAttemptService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class ExecutionAttemptServiceImpl extends ServiceImpl<ExecutionAttemptMapper, ExecutionAttempt>
        implements ExecutionAttemptService {
}
