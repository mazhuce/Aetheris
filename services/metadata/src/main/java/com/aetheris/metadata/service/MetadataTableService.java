package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.MetadataTable;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface MetadataTableService extends IService<MetadataTable> {

    /**
     * 按关键词检索表，供控制台顶部全局搜索使用。
     *
     * <p>关键词为空时返回空列表（而非全表），避免空输入把整张表拉回前端。
     *
     * @param keyword 关键词；同时匹配表名、表注释与业务域
     * @param limit   返回条数上限
     */
    List<MetadataTable> search(String keyword, int limit);
}
