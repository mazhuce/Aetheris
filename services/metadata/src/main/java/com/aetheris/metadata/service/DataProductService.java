package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.DataProduct;
import com.aetheris.metadata.mapper.DataProductMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;

/** 数据产品服务：产品台账检索与生命周期维护，编码唯一。 */
@Service
public class DataProductService {

    private final DataProductMapper mapper;

    public DataProductService(DataProductMapper mapper) {
        this.mapper = mapper;
    }

    /** 列表，可选 keyword 同时模糊匹配产品名称与编码。 */
    public List<DataProduct> list(String keyword) {
        LambdaQueryWrapper<DataProduct> wrapper = new LambdaQueryWrapper<DataProduct>()
                .orderByDesc(DataProduct::getId);
        if (StringUtils.hasText(keyword)) {
            String trimmed = keyword.trim();
            wrapper.and(w -> w.like(DataProduct::getProductName, trimmed)
                    .or().like(DataProduct::getProductCode, trimmed));
        }
        return mapper.selectList(wrapper);
    }

    public DataProduct getById(Long id) {
        return mapper.selectById(id);
    }

    /** 编码是否已被占用；excludeId 用于更新时排除自身。 */
    public boolean codeExists(String code, Long excludeId) {
        return mapper.selectCount(new LambdaQueryWrapper<DataProduct>()
                .eq(DataProduct::getProductCode, code)
                .ne(excludeId != null, DataProduct::getId, excludeId)) > 0;
    }

    public DataProduct create(DataProduct product) {
        mapper.insert(product);
        return product;
    }

    public boolean update(DataProduct product) {
        product.setUpdatedAt(OffsetDateTime.now());
        return mapper.updateById(product) > 0;
    }

    public boolean remove(Long id) {
        return mapper.deleteById(id) > 0;
    }
}
