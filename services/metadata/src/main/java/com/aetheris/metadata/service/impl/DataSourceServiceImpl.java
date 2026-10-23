package com.aetheris.metadata.service.impl;

import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.mapper.DataSourceMapper;
import com.aetheris.metadata.service.DataSourceService;
import com.aetheris.metadata.service.DataSourceSecretService;
import com.aetheris.metadata.service.DataSourceConnectionInputValidator;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;

@Service
public class DataSourceServiceImpl extends ServiceImpl<DataSourceMapper, DataSource>
        implements DataSourceService {

    private final DataSourceSecretService secretService;

    public DataSourceServiceImpl(DataSourceSecretService secretService) {
        this.secretService = secretService;
    }

    @Override
    public boolean save(DataSource entity) {
        DataSourceConnectionInputValidator.validateCreate(entity);
        prepare(entity, null);
        return super.save(entity);
    }

    @Override
    public boolean updateById(DataSource entity) {
        if (entity == null || entity.getId() == null) return false;
        DataSource previous = getById(entity.getId());
        if (previous == null) return false;
        if (entity.getConnectionRevision() != null && !java.util.Objects.equals(entity.getConnectionRevision(), previous.getConnectionRevision())) return false;
        DataSourceConnectionInputValidator.validateUpdate(entity, previous);
        prepare(entity, previous);
        // Credential CAS: a stale ordinary edit must never revive a writer disabled by another request.
        var guard = new LambdaUpdateWrapper<DataSource>().eq(DataSource::getId, entity.getId());
        if (previous.getConnectionRevision() == null) guard.isNull(DataSource::getConnectionRevision); else guard.eq(DataSource::getConnectionRevision, previous.getConnectionRevision());
        if (previous.getUsername() == null) guard.isNull(DataSource::getUsername); else guard.eq(DataSource::getUsername, previous.getUsername());
        if (previous.getPassword() == null) guard.isNull(DataSource::getPassword); else guard.eq(DataSource::getPassword, previous.getPassword());
        if (previous.getWriteUsername() == null) guard.isNull(DataSource::getWriteUsername); else guard.eq(DataSource::getWriteUsername, previous.getWriteUsername());
        if (previous.getWritePassword() == null) guard.isNull(DataSource::getWritePassword); else guard.eq(DataSource::getWritePassword, previous.getWritePassword());
        return super.update(entity, guard);
    }

    private void prepare(DataSource entity, DataSource previous) {
        if (entity == null) throw new IllegalArgumentException("数据源不能为空");
        if (previous != null) {
            // An API supplies new plaintext, never another snapshot's opaque ciphertext. Versioned internal migration is allowed.
            if (entity.getConnectionRevision() == null && (
                    secretService.isEncrypted(entity.getPassword()) && !java.util.Objects.equals(entity.getPassword(), previous.getPassword())
                    || secretService.isEncrypted(entity.getWritePassword()) && !java.util.Objects.equals(entity.getWritePassword(), previous.getWritePassword())))
                throw new IllegalArgumentException("旧密文不能作为新密码提交；请刷新并提供新明文密码");
            if (entity.getUsername() == null) entity.setUsername(previous.getUsername());
            if (blank(entity.getPassword())) {
                if (!java.util.Objects.equals(entity.getUsername(), previous.getUsername()))
                    throw new IllegalArgumentException("更换读取账户必须提供新密码");
                entity.setPassword(previous.getPassword());
            } else if (!java.util.Objects.equals(entity.getUsername(), previous.getUsername()) && secretService.isEncrypted(entity.getPassword())) {
                throw new IllegalArgumentException("更换读取账户必须提供新的明文密码");
            }
        }
        if (Boolean.TRUE.equals(entity.getDisableWriteCredentials())) {
            if (!blank(entity.getWriteUsername()) || !blank(entity.getWritePassword()))
                throw new IllegalArgumentException("禁用写入时不能同时提交写凭据");
            entity.setWriteUsername(null); entity.setWritePassword(null);
        } else {
            if (previous != null && blank(entity.getWriteUsername())) entity.setWriteUsername(previous.getWriteUsername());
            if (previous != null && blank(entity.getWritePassword())) {
                if (!java.util.Objects.equals(entity.getWriteUsername(), previous.getWriteUsername()))
                    throw new IllegalArgumentException("更换写入账户必须提供新密码");
                entity.setWritePassword(previous.getWritePassword());
            } else if (previous != null && !java.util.Objects.equals(entity.getWriteUsername(), previous.getWriteUsername())
                    && secretService.isEncrypted(entity.getWritePassword())) {
                throw new IllegalArgumentException("更换写入账户必须提供新的明文密码");
            }
            if (blank(entity.getWriteUsername()) != blank(entity.getWritePassword()))
                throw new IllegalArgumentException("写入账户和密码必须完整配置");
            if (!blank(entity.getWriteUsername()) && entity.getWriteUsername().trim().equalsIgnoreCase(entity.getUsername() == null ? "" : entity.getUsername().trim()))
                throw new IllegalArgumentException("写入账户必须与读取账户不同");
            if (blank(entity.getWriteUsername()) && blank(entity.getWritePassword())) {
                entity.setWriteUsername(null); entity.setWritePassword(null);
            }
        }
        entity.setPassword(secretService.encrypt(entity.getPassword()));
        entity.setWritePassword(secretService.encrypt(entity.getWritePassword()));
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
