package com.aetheris.workflow.service;

import com.aetheris.workflow.service.ManagedSparkCatalogService.ManagedArtifactView;
import com.aetheris.workflow.service.ManagedSparkCatalogService.ArtifactInput;
import com.aetheris.workflow.service.ManagedSparkCatalogService.SparkJobView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ManagedSparkCatalogServiceTest {

    @Test
    void newArtifactRequiresAnImmutableGitSourceBinding() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SparkBatchExecutionAdapter adapter = mock(SparkBatchExecutionAdapter.class);
        ManagedSparkCatalogService catalog = new ManagedSparkCatalogService(
                jdbc, new ObjectMapper(), adapter, mock(GitRepositoryService.class));

        assertThrows(IllegalArgumentException.class, () -> catalog.registerArtifact(
                new ArtifactInput("artifact", "1.0.0", "approved_job.py", "a".repeat(64),
                        null, null, null), "alice"));
        assertThrows(IllegalArgumentException.class, () -> catalog.registerArtifact(
                new ArtifactInput("artifact", "1.0.0", "approved_job.py", "a".repeat(64),
                        8L, "b".repeat(40), "spark/./approved_job.py"), "alice"));

        verifyNoInteractions(jdbc, adapter);
    }

    @Test
    void artifactCreatorCannotApproveOwnArtifact() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SparkBatchExecutionAdapter adapter = mock(SparkBatchExecutionAdapter.class);
        ManagedSparkCatalogService catalog = spy(new ManagedSparkCatalogService(
                jdbc, new ObjectMapper(), adapter, mock(GitRepositoryService.class)));
        ManagedArtifactView artifact = new ManagedArtifactView(
                2L, "artifact", "1.0.0", "pyspark", "approved_job.py", "a".repeat(64),
                8L, "jobs", "active", "b".repeat(40), "spark/approved_job.py",
                "c".repeat(40), "a".repeat(64), 128L, "verified", "d".repeat(40),
                "reviewer", null,
                "draft", "alice", null, null, null, null, null);
        doReturn(artifact).when(catalog).artifact(2L);

        assertThrows(IllegalStateException.class, () -> catalog.approveArtifact(2L, "alice"));

        verifyNoInteractions(adapter);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void jobOwnerCannotPublishOwnJob() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SparkBatchExecutionAdapter adapter = mock(SparkBatchExecutionAdapter.class);
        ManagedSparkCatalogService catalog = spy(new ManagedSparkCatalogService(
                jdbc, new ObjectMapper(), adapter, mock(GitRepositoryService.class)));
        SparkJobView job = new SparkJobView(
                3L, "job", 1, 1L, "local", "active", 2L, "artifact",
                "approved_job.py", "a".repeat(64), "approved",
                8L, "jobs", "active", "b".repeat(40), "spark/approved_job.py",
                "c".repeat(40), "a".repeat(64), 128L, "verified", "d".repeat(40),
                "reviewer", null,
                JsonNodeFactory.instance.objectNode(), JsonNodeFactory.instance.objectNode(),
                List.of("hive_prod.dwd.dwd_order"), null,
                "hdfs://localhost:9000/user/aetheris/managed/result", "overwrite_partition",
                1, 768, 1800, 0, "draft", null, "alice", null, null,
                null, null, null);
        doReturn(job).when(catalog).job(3L);

        assertThrows(IllegalStateException.class, () -> catalog.publishJob(3L, "alice"));

        verifyNoInteractions(adapter);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void approvalFailsClosedWhenGitBlobDigestChanged() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SparkBatchExecutionAdapter adapter = mock(SparkBatchExecutionAdapter.class);
        GitRepositoryService repositories = mock(GitRepositoryService.class);
        ManagedSparkCatalogService catalog = spy(new ManagedSparkCatalogService(
                jdbc, new ObjectMapper(), adapter, repositories));
        ManagedArtifactView artifact = new ManagedArtifactView(
                2L, "artifact", "1.0.0", "pyspark", "approved_job.py", "a".repeat(64),
                8L, "jobs", "active", "b".repeat(40), "spark/approved_job.py",
                "c".repeat(40), "a".repeat(64), 128L, "verified", "d".repeat(40),
                "creator", null, "draft", "creator", null, null, null, null, null);
        doReturn(artifact).when(catalog).artifact(2L);
        when(repositories.verifySource(8L, "b".repeat(40), "spark/approved_job.py"))
                .thenReturn(new GitRemoteInspector.SourceEvidence(
                        new GitRemoteInspector.RevisionEvidence(
                                "b".repeat(40), "verified", "d".repeat(40), null),
                        "c".repeat(40), "e".repeat(64), 128));

        assertThrows(IllegalStateException.class, () -> catalog.approveArtifact(2L, "reviewer"));

        verifyNoInteractions(adapter);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
