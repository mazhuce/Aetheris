package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.ApiAsset;
import com.aetheris.metadata.mapper.ApiAssetMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;

/** API 资产服务：接口台账的检索与登记，编码唯一。 */
@Service
public class ApiAssetService {

    private final ApiAssetMapper mapper;

    public ApiAssetService(ApiAssetMapper mapper) {
        this.mapper = mapper;
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
        mapper.insert(asset);
        return asset;
    }

    public boolean update(ApiAsset asset) {
        asset.setUpdatedAt(OffsetDateTime.now());
        return mapper.updateById(asset) > 0;
    }

    public boolean remove(Long id) {
        return mapper.deleteById(id) > 0;
    }
}
