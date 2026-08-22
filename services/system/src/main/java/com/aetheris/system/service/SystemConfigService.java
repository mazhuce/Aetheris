package com.aetheris.system.service;

import com.aetheris.system.entity.SystemConfig;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.Optional;

public interface SystemConfigService extends IService<SystemConfig> {

    Optional<SystemConfig> findByKey(String key);
}
