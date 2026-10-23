package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.QueryHistory;
import com.aetheris.metadata.dto.QueryHistoryResponse;
import com.aetheris.metadata.mapper.QueryHistoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 查询历史服务：只读写稳定 UID 的本人记录，旧 NULL 归属保持隔离。
 * 历史是辅助审计与复用，失败也不影响主查询流程（调用方吞异常）。
 */
@Service
public class QueryHistoryService {

    private static final int MAX_HISTORY = 200;
    private static final int CLEAR_BATCH = 500;

    private final QueryHistoryMapper mapper;

    public QueryHistoryService(QueryHistoryMapper mapper) {
        this.mapper = mapper;
    }

    public void record(QueryHistory history) {
        requireUid(history.getOwnerUid());
        if (!"success".equals(history.getStatus())) {
            String code = QueryHistoryResponse.safeCode(history.getErrorCode());
            history.setErrorCode(code); history.setErrorMessage(QueryHistoryResponse.message(code));
        } else { history.setErrorCode(null); history.setErrorMessage(null); }
        mapper.insert(history);
    }

    public List<QueryHistoryResponse> list(long ownerUid, String status) {
        requireUid(ownerUid);
        return mapper.selectList(new LambdaQueryWrapper<QueryHistory>()
                .eq(QueryHistory::getOwnerUid, ownerUid)
                .eq(status != null && !status.isBlank(), QueryHistory::getStatus, status)
                .orderByDesc(QueryHistory::getId)
                .last("LIMIT " + MAX_HISTORY)).stream().map(QueryHistoryResponse::from).toList();
    }

    public boolean delete(long ownerUid, Long id) {
        requireUid(ownerUid);
        return mapper.delete(new LambdaQueryWrapper<QueryHistory>()
                .eq(QueryHistory::getOwnerUid, ownerUid).eq(QueryHistory::getId, id)) > 0;
    }

    public ClearResult clear(long ownerUid) {
        requireUid(ownerUid);
        var batch = mapper.selectList(new LambdaQueryWrapper<QueryHistory>()
                .select(QueryHistory::getId).eq(QueryHistory::getOwnerUid, ownerUid)
                .orderByAsc(QueryHistory::getId).last("LIMIT " + CLEAR_BATCH));
        int deleted = batch.isEmpty() ? 0 : mapper.delete(new LambdaQueryWrapper<QueryHistory>()
                .eq(QueryHistory::getOwnerUid, ownerUid).in(QueryHistory::getId,
                        batch.stream().map(QueryHistory::getId).toList()));
        boolean more = !mapper.selectList(new LambdaQueryWrapper<QueryHistory>()
                .select(QueryHistory::getId).eq(QueryHistory::getOwnerUid, ownerUid).last("LIMIT 1")).isEmpty();
        return new ClearResult(deleted, more);
    }
    public record ClearResult(int deleted, boolean hasMore) { }
    private static void requireUid(Long uid) {
        if (uid == null || uid <= 0) throw new IllegalArgumentException("缺少稳定用户身份");
    }
}
