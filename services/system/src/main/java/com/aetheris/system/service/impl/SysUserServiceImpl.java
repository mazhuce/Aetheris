package com.aetheris.system.service.impl;

import com.aetheris.system.entity.SysUser;
import com.aetheris.system.mapper.SysUserMapper;
import com.aetheris.system.service.SysUserService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser>
        implements SysUserService {
}
