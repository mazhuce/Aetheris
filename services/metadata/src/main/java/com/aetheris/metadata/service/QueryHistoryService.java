package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.QueryHistory;
import com.aetheris.metadata.mapper.QueryHistoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 查询历史服务：只读写本人记录（username 由 controller 从网关头取，不可伪造）。
 * 历史是辅助审计与复用，失败也不影响主查询流程（调用方吞异常）。
 */
@Service
public class QueryHistoryService {

    private static final int MAX_HISTORY = 200;

    private final QueryHistoryMapper mapper;

    public QueryHistoryService(QueryHistoryMapper mapper) {
        this.mapper = mapper;
    }

    public void record(QueryHistory history) {
        mapper.insert(history);
    }

    public List<QueryHistory> list(String username, String status) {
        return mapper.selectList(new LambdaQueryWrapper<QueryHistory>()
                .eq(QueryHistory::getUsername, username)
                .eq(status != null && !status.isBlank(), QueryHistory::getStatus, status)
                .orderByDesc(QueryHistory::getId)
                .last("LIMIT " + MAX_HISTORY));
    }

    public boolean delete(String username, Long id) {
        QueryHistory existing = mapper.selectById(id);
        if (existing == null || !existing.getUsername().equals(username)) {
            return false; // 不存在或不是本人的记录
        }
        return mapper.deleteById(id) > 0;
    }

    public int clear(String username) {
        return mapper.delete(new LambdaQueryWrapper<QueryHistory>()
                .eq(QueryHistory::getUsername, username));
    }
}
