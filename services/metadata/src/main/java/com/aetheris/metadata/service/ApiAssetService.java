package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.ApiAsset;
import com.aetheris.metadata.mapper.ApiAssetMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.List;

/** API 资产服务：接口台账的检索与登记，编码唯一。 */
@Service
public class ApiAssetService {

    private final ApiAssetMapper mapper;
    private final JdbcTemplate jdbc;

    public ApiAssetService(ApiAssetMapper mapper, JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    /** 列表，可选 keyword 同时模糊匹配资产名称与编码。 */
    public List<ApiAsset> list(String keyword) {
        LambdaQueryWrapper<ApiAsset> wrapper = new LambdaQueryWrapper<ApiAsset>()
                .orderByDesc(ApiAsset::getId);
        if (StringUtils.hasText(keyword)) {
            String trimmed = keyword.trim();
            wrapper.and(w -> w.like(ApiAsset::getAssetName, trimmed)
                    .or().like(ApiAsset::getAssetCode, trimmed));
        }
        return mapper.selectList(wrapper);
    }

    public ApiAsset getById(Long id) {
        return mapper.selectById(id);
    }

    /** 编码是否已被占用；excludeId 用于更新时排除自身。 */
    public boolean codeExists(String code, Long excludeId) {
        return mapper.selectCount(new LambdaQueryWrapper<ApiAsset>()
                .eq(ApiAsset::getAssetCode, code)
                .ne(excludeId != null, ApiAsset::getId, excludeId)) > 0;
    }

    public ApiAsset create(ApiAsset asset) {
        normalize(asset);
        asset.setStatus("registered");
        asset.setPublishedBy(null);
        asset.setPublishedAt(null);
        mapper.insert(asset);
        return asset;
    }

    public boolean update(ApiAsset asset) {
        ApiAsset existing = getById(asset.getId());
        if (existing == null) return false;
        if ("published".equals(existing.getStatus()) || "deprecated".equals(existing.getStatus())) {
            throw new IllegalStateException("已发布或已废弃的 API 资产不能直接编辑，请先创建新版本");
        }
        normalize(asset);
        asset.setStatus(existing.getStatus() == null ? "registered" : existing.getStatus());
        asset.setPublishedBy(null);
        asset.setPublishedAt(null);
        asset.setUpdatedAt(OffsetDateTime.now());
        return mapper.updateById(asset) > 0;
    }

    public ApiAsset publish(Long id, String username) {
        ApiAsset asset = require(id);
        if (!"registered".equals(asset.getStatus())) {
            throw new IllegalStateException("只有 registered 状态的 API 资产可以发布");
        }
        normalize(asset);
        validateTarget(asset);
        OffsetDateTime now = OffsetDateTime.now();
        asset.setStatus("published");
        asset.setPublishedBy(username);
        asset.setPublishedAt(now);
        asset.setUpdatedAt(now);
        mapper.updateById(asset);
        return require(id);
    }

    public ApiAsset deprecate(Long id) {
        ApiAsset asset = require(id);
        if (!"published".equals(asset.getStatus())) {
            throw new IllegalStateException("只有 published 状态的 API 资产可以废弃");
        }
        asset.setStatus("deprecated");
        asset.setUpdatedAt(OffsetDateTime.now());
        mapper.updateById(asset);
        return require(id);
    }

    public boolean remove(Long id) {
        ApiAsset asset = getById(id);
        if (asset != null && ("published".equals(asset.getStatus()) || "deprecated".equals(asset.getStatus()))) {
            throw new IllegalStateException("已发布或已废弃的 API 资产不能删除");
        }
        return mapper.deleteById(id) > 0;
    }

    public ApiAsset require(Long id) {
        ApiAsset asset = getById(id);
        if (asset == null) throw new NotFoundException("API 资产不存在");
        return asset;
    }

    public ApiAsset publishedByCode(String code) {
        return mapper.selectOne(new LambdaQueryWrapper<ApiAsset>()
                .eq(ApiAsset::getAssetCode, code)
                .eq(ApiAsset::getStatus, "published"));
    }

    private void normalize(ApiAsset asset) {
        if (asset == null) throw new IllegalArgumentException("API 资产不能为空");
        if (!StringUtils.hasText(asset.getAssetCode()) || !asset.getAssetCode().matches("[A-Za-z][A-Za-z0-9_]{1,63}")) {
            throw new IllegalArgumentException("API 资产编码格式无效");
        }
        if (!StringUtils.hasText(asset.getAssetName()) || asset.getAssetName().trim().length() > 128) {
            throw new IllegalArgumentException("API 资产名称不能为空且不能超过 128 个字符");
        }
        if (!StringUtils.hasText(asset.getApiPath()) || !asset.getApiPath().startsWith("/api/") || asset.getApiPath().contains("..")) {
            throw new IllegalArgumentException("API 路径必须是 /api/ 开头的安全路径");
        }
        asset.setAssetCode(asset.getAssetCode().trim());
        asset.setAssetName(asset.getAssetName().trim());
        asset.setApiPath(asset.getApiPath().trim());
        asset.setHttpMethod(StringUtils.hasText(asset.getHttpMethod()) ? asset.getHttpMethod().trim().toUpperCase(Locale.ROOT) : "POST");
        if (!List.of("GET", "POST").contains(asset.getHttpMethod())) {
            throw new IllegalArgumentException("API 资产仅支持 GET 或 POST");
        }
        asset.setVersion(StringUtils.hasText(asset.getVersion()) ? asset.getVersion().trim() : "v1");
        if (!asset.getVersion().matches("v[1-9][0-9]*")) {
            throw new IllegalArgumentException("API 版本必须使用 v1、v2 形式");
        }
        if (StringUtils.hasText(asset.getTargetType())) asset.setTargetType(asset.getTargetType().trim().toLowerCase(Locale.ROOT));
        if (StringUtils.hasText(asset.getTargetRef())) asset.setTargetRef(asset.getTargetRef().trim());
    }

    private void validateTarget(ApiAsset asset) {
        if (!"POST".equals(asset.getHttpMethod())) {
            throw new IllegalArgumentException("可执行 API 资产必须使用 POST 方法");
        }
        if (!StringUtils.hasText(asset.getTargetType())
                || !List.of("metric", "table").contains(asset.getTargetType())
                || !StringUtils.hasText(asset.getTargetRef())) {
            throw new IllegalArgumentException("发布 API 前必须绑定 metric 或 table 执行目标");
        }
        if ("metric".equals(asset.getTargetType())) {
            if (!asset.getTargetRef().matches("[A-Za-z][A-Za-z0-9_]{1,63}")) {
                throw new IllegalArgumentException("metric 执行目标编码格式无效");
            }
            Integer count = jdbc.queryForObject("SELECT count(*) FROM metric_definition WHERE metric_code = ? AND status = 'published' AND published_version IS NOT NULL", Integer.class, asset.getTargetRef());
            if (count == null || count == 0) throw new IllegalArgumentException("执行目标指标不存在或未发布");
        } else {
            long tableId;
            try { tableId = Long.parseLong(asset.getTargetRef()); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("table 执行目标必须是正整数 ID"); }
            if (tableId <= 0) throw new IllegalArgumentException("table 执行目标必须是正整数 ID");
            Integer count = jdbc.queryForObject("SELECT count(*) FROM metadata_table WHERE id = ? AND status <> 'stale'", Integer.class, tableId);
            if (count == null || count == 0) throw new IllegalArgumentException("执行目标数据表不存在或已失效");
        }
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }
}
