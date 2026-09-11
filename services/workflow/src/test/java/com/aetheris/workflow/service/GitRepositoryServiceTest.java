package com.aetheris.workflow.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitRepositoryServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void synchronizationCannotCommitAfterAConcurrentDisable() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        GitRemoteInspector inspector = mock(GitRemoteInspector.class);
        ResultSet observed = repositoryRow("active");
        ResultSet locked = repositoryRow("disabled");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            RowMapper<Object> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(sql.contains("FOR UPDATE") ? locked : observed, 0));
        });
        when(inspector.inspect("https://github.com/acme/jobs.git", "main", null, null))
                .thenReturn(new GitRemoteInspector.RemoteHead(
                        "https://github.com/acme/jobs.git", "main", "b".repeat(40)));
        GitRepositoryService service = new GitRepositoryService(jdbc, noOpTransactions(), inspector);

        assertThrows(IllegalStateException.class, () -> service.synchronize(9L, "admin"));

        verify(inspector).inspect("https://github.com/acme/jobs.git", "main", null, null);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    private static ResultSet repositoryRow(String status) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(9L);
        when(row.getString("remote_url")).thenReturn("https://github.com/acme/jobs.git");
        when(row.getString("default_branch")).thenReturn("main");
        when(row.getString("credential_username")).thenReturn(null);
        when(row.getString("credential_ref")).thenReturn(null);
        when(row.getString("status")).thenReturn(status);
        when(row.getString("current_commit_sha")).thenReturn("a".repeat(40));
        return row;
    }

    private static PlatformTransactionManager noOpTransactions() {
        return new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }
            @Override public void commit(TransactionStatus status) { }
            @Override public void rollback(TransactionStatus status) { }
        };
    }
}
