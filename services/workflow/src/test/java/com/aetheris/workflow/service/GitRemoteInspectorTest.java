package com.aetheris.workflow.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitRemoteInspectorTest {

    private final GitRemoteInspector inspector = new GitRemoteInspector(
            "github.com,gitee.com", 8, name -> null);

    @Test
    void normalizesOnlyAllowlistedCredentialFreeHttpsRepositories() {
        assertEquals("https://github.com/Aetheris/jobs.git",
                inspector.normalizeRemoteUrl("https://github.com/Aetheris/jobs"));

        assertThrows(IllegalArgumentException.class,
                () -> inspector.normalizeRemoteUrl("http://github.com/Aetheris/jobs.git"));
        assertThrows(IllegalArgumentException.class,
                () -> inspector.normalizeRemoteUrl("https://user:secret@github.com/Aetheris/jobs.git"));
        assertThrows(IllegalArgumentException.class,
                () -> inspector.normalizeRemoteUrl("https://127.0.0.1/Aetheris/jobs.git"));
        assertThrows(IllegalArgumentException.class,
                () -> inspector.normalizeRemoteUrl("https://github.com/Aetheris/%2e%2e/jobs.git"));
        assertThrows(IllegalArgumentException.class,
                () -> inspector.normalizeRemoteUrl("https://github.com:8443/Aetheris/jobs.git"));
    }

    @Test
    void rejectsGitRefTraversalAndControlSyntax() {
        assertEquals("release/2026.09", GitRemoteInspector.normalizeBranch("release/2026.09"));

        assertThrows(IllegalArgumentException.class, () -> GitRemoteInspector.normalizeBranch("feature/./job"));
        assertThrows(IllegalArgumentException.class, () -> GitRemoteInspector.normalizeBranch("feature/../job"));
        assertThrows(IllegalArgumentException.class, () -> GitRemoteInspector.normalizeBranch("main.lock"));
        assertThrows(IllegalArgumentException.class, () -> GitRemoteInspector.normalizeBranch("main@{1}"));
    }

    @Test
    void credentialReferenceNeverAcceptsAnArbitraryEnvironmentVariable() {
        assertThrows(IllegalArgumentException.class, () -> inspector.inspect(
                "https://github.com/Aetheris/jobs.git", "main", "git", "env://DATABASE_PASSWORD"));
        assertThrows(IllegalStateException.class, () -> inspector.inspect(
                "https://github.com/Aetheris/jobs.git", "main", "git",
                "env://AETHERIS_GIT_CREDENTIAL_JOBS"));
    }
}
