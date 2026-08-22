package com.aetheris.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aetheris.system.entity.SystemConfig;
import com.aetheris.system.mapper.SystemConfigMapper;
import com.aetheris.system.service.SystemConfigService;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class SystemConfigServiceImpl extends ServiceImpl<SystemConfigMapper, SystemConfig>
        implements SystemConfigService {

    @Override
    public Optional<SystemConfig> findByKey(String key) {
        LambdaQueryWrapper<SystemConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SystemConfig::getConfigKey, key);
        return Optional.ofNullable(getOne(wrapper));
    }
}
