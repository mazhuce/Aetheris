package com.aetheris.governance.service.impl;

import com.aetheris.governance.entity.GovernancePolicy;
import com.aetheris.governance.mapper.GovernancePolicyMapper;
import com.aetheris.governance.service.GovernancePolicyService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class GovernancePolicyServiceImpl extends ServiceImpl<GovernancePolicyMapper, GovernancePolicy>
        implements GovernancePolicyService {
}
