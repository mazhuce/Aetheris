package com.aetheris.workflow.service;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.GpgConfig;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.SignatureVerifiers;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.bouncycastle.openpgp.PGPUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitRemoteInspectorTest {

    private static final String SIGNER_FINGERPRINT = "ce03a9a2ad1a525a4a8a9eb9aeff6c99dea2abda";
    private static final String SIGNED_COMMIT = "dHJlZSBmYjI0ZmZjMzEyYzgzYjkwN2M4OWVlZTljNDQ3NWRjYTgzZDEwY2E0CmF1dGhvciBBZXRoZXJpcyBNNWIgVGVzdCA8bTViQGV4YW1wbGUuaW52YWxpZD4gMTc4OTE1MzY4NSArMDgwMApjb21taXR0ZXIgQWV0aGVyaXMgTTViIFRlc3QgPG01YkBleGFtcGxlLmludmFsaWQ+IDE3ODkxNTM2ODUgKzA4MDAKZ3Bnc2lnIC0tLS0tQkVHSU4gUEdQIFNJR05BVFVSRS0tLS0tCiAKIGlRRXpCQUFCQ0FBZEZpRUV6Z09wb3EwYVVscEtpcDY1cnY5c21kNmlxOW9GQW1xa1VaVUFDZ2tRcnY5c21kNmkKIHE5cU45UWdBbjlLV1ppaU9ickJsMEJReHVPN3V0TUdKQWpxS2xWTDFNWkVRSWMzbnBobFZXRjRQSXZ6a2dNazkKIFFMbEtUeGIyV2grNVlxZCs5dW9lQk8rYlA3QmQ4V1lzOHZ1STdYdlE4dnRpSGhTVjNwbFc1RjZkbXBoNHA3UmEKIG42WVVlaml2WGtsZE1maVJFV2p2TW5Rb3U1TUJYYnZTOVVsdG1aQUk0VXlKNkJUQmJEWmJnWEhaWnEzVzJmZHMKIDA2Nk5GVE1jcHhhekxUU0V2Z1IyTmppMVhucHVZRmJFNUpCVVd4dVZhOTBUaTROUGthZFJzOHdpMU5YSlFZYUcKIEtBZDQwekRVVkUrK2ptaTFTWlZIT1lpUzlzbVpCNXVZb2ZKdFRqQzFpRmc5Q0trNXNLMnRVUlVRSHVCMGg0d2oKIFdVdGE2WmMwTVJmakJEa0dQbVJSQjdIR29NZWpuUT09CiA9WkxMaQogLS0tLS1FTkQgUEdQIFNJR05BVFVSRS0tLS0tCgpzaWduZWQgZml4dHVyZQo=";
    private static final String PUBLIC_KEY = """
            -----BEGIN PGP PUBLIC KEY BLOCK-----

            mQENBGqkUZQBCADFKvZiLoMMb3Xy+MfiTcqxiAZPJDCiKUdZ1HY/zUA9mNk4pSZ2
            FvsfnquZXlFbFMu/SnpsfhSZIT/zSGddjBkY5MnbwVwL6hPE0q5xtQUvU1fqldyW
            o7erUKLL6ws/dt4NXPJZm0BbXYCf9DHJ1HZwJbMd9D+PVy7XOtFf2ekGuX/0NJbn
            3KpXhwG7xbZSvpgYx7rK5JPLEZmrzAZdrIi2uGem6UqSn7r7TuAyrfcKOyeovGVE
            GWRgPOn0w2kL+83YZ/KV5pXSzuGuxewzIO12fvluXrrmfXb/fPb/mDz1b40bahG8
            VCdVaPjqyij8OMx1QsmrRsZ9Snzq6P4WkJGDABEBAAG0J0FldGhlcmlzIE01YiBU
            ZXN0IDxtNWJAZXhhbXBsZS5pbnZhbGlkPokBUQQTAQgAOxYhBM4DqaKtGlJaSoqe
            ua7/bJneoqvaBQJqpFGUAhsDBQsJCAcCAiICBhUKCQgLAgQWAgMBAh4HAheAAAoJ
            EK7/bJneoqvabwcIAKb2pWEXsoigiJdEbWfWvO9wZB6cRWOS8z+fDsoEJy88nvoO
            eHuddY4PveeGhIOZdOtoaLT2LvXy1GWrFqw5rwg9O+XlXR/Blhqq2vRibaVlZehO
            i24PclPIJFhutjNWrPRrb0ktrb/hUp8bMsilJR67qjOBZl5WLzF6V9H5fTyOPlTA
            rjg1l4e51M0B4r3f45lLxwV8s/BgZJ0nZ6x0nLF10NOeWQkagm5/utMQEDYnlpUt
            xdM7AP5VSdGm5boxTN/U7bm+urPAdtaoH55YCDPkD27eWMzHghMUzNr0G3kyNRbJ
            3v8HoCAzoXM4s4tKbVeYaj0eU8VgUGLMCyNBeWg=
            =ojDa
            -----END PGP PUBLIC KEY BLOCK-----
            """;

    @TempDir
    static Path gpgHome;

    private final GitRemoteInspector inspector = new GitRemoteInspector(
            "github.com,gitee.com", 8, name -> null);

    @BeforeAll
    static void configurePublicKeyring() throws Exception {
        try (var decoded = PGPUtil.getDecoderStream(new ByteArrayInputStream(
                PUBLIC_KEY.getBytes(StandardCharsets.US_ASCII)))) {
            Files.copy(decoded, gpgHome.resolve("pubring.gpg"));
        }
        System.setProperty("jgit.gpg.home", gpgHome.toString());
    }

    @AfterAll
    static void clearPublicKeyringProperty() {
        System.clearProperty("jgit.gpg.home");
    }

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

    @Test
    void openPgpVerifierModuleIsInstalled() {
        assertNotNull(SignatureVerifiers.get(GpgConfig.GpgFormat.OPENPGP));
    }

    @Test
    void verifiesRealOpenPgpSignedCommitWithConfiguredPublicKey(@TempDir Path directory) throws Exception {
        try (Repository repository = new FileRepositoryBuilder()
                .setGitDir(directory.resolve("repo.git").toFile()).setBare().build()) {
            repository.create(true);
            var inserter = repository.newObjectInserter();
            var commitId = inserter.insert(Constants.OBJ_COMMIT, Base64.getDecoder().decode(SIGNED_COMMIT));
            inserter.flush();
            inserter.close();
            try (RevWalk walk = new RevWalk(repository)) {
                var commit = walk.parseCommit(commitId);
                var direct = SignatureVerifiers.verify(
                        repository, new GpgConfig(repository.getConfig()), commit);
                assertNotNull(direct);
                GitRemoteInspector.SignatureEvidence evidence =
                        inspector.verifySignature(repository, commit);
                assertEquals("verified", evidence.status());
                assertEquals(SIGNER_FINGERPRINT, evidence.fingerprint());
            }
        }
    }

    @Test
    void rejectsTamperedOpenPgpSignedCommit(@TempDir Path directory) throws Exception {
        byte[] original = Base64.getDecoder().decode(SIGNED_COMMIT);
        byte[] tampered = new String(original, StandardCharsets.UTF_8)
                .replace("signed fixture", "signed fixturf")
                .getBytes(StandardCharsets.UTF_8);
        try (Repository repository = new FileRepositoryBuilder()
                .setGitDir(directory.resolve("repo.git").toFile()).setBare().build()) {
            repository.create(true);
            var inserter = repository.newObjectInserter();
            var commitId = inserter.insert(Constants.OBJ_COMMIT, tampered);
            inserter.flush();
            inserter.close();
            try (RevWalk walk = new RevWalk(repository)) {
                var commit = walk.parseCommit(commitId);
                assertThrows(IllegalStateException.class,
                        () -> inspector.verifySignature(repository, commit));
            }
        }
    }

    @Test
    void readsExactRegularBlobAndComputesSha256(@TempDir Path directory) throws Exception {
        String content = "from pyspark.sql import SparkSession\nprint('verified')\n";
        try (Git git = Git.init().setDirectory(directory.toFile()).call()) {
            Path source = directory.resolve("spark/job.py");
            Files.createDirectories(source.getParent());
            Files.writeString(source, content, StandardCharsets.UTF_8);
            git.add().addFilepattern("spark/job.py").call();
            var commit = git.commit().setMessage("job").setAuthor("Alice", "alice@example.test")
                    .setCommitter("Alice", "alice@example.test").call();
            var sourceRef = git.getRepository().updateRef("refs/aetheris/source");
            sourceRef.setNewObjectId(commit);
            sourceRef.update();

            GitRemoteInspector.SourceEvidence evidence = inspector.inspectRepository(
                    git.getRepository(), commit.name(), "spark/job.py", "optional", Set.of());

            assertNotNull(evidence.blobId());
            assertEquals(content.getBytes(StandardCharsets.UTF_8).length, evidence.blobSize());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8))), evidence.blobSha256());
            assertEquals("unsigned", evidence.revision().signatureStatus());
        }
    }

    @Test
    void rejectsUnsignedCommitWhenSignatureIsRequired(@TempDir Path directory) throws Exception {
        try (Git git = Git.init().setDirectory(directory.toFile()).call()) {
            Files.writeString(directory.resolve("job.py"), "print('job')\n", StandardCharsets.UTF_8);
            git.add().addFilepattern("job.py").call();
            var commit = git.commit().setMessage("job").setAuthor("Alice", "alice@example.test")
                    .setCommitter("Alice", "alice@example.test").call();
            var sourceRef = git.getRepository().updateRef("refs/aetheris/source");
            sourceRef.setNewObjectId(commit);
            sourceRef.update();

            assertThrows(IllegalStateException.class, () -> inspector.inspectRepository(
                    git.getRepository(), commit.name(), "job.py", "required", Set.of("a".repeat(40))));
        }
    }

    @Test
    void enforcesTrustedSignerFingerprint() {
        GitRemoteInspector.SignatureEvidence signed = new GitRemoteInspector.SignatureEvidence(
                "verified", "a".repeat(40), null);
        GitRemoteInspector.SignatureEvidence unsigned = new GitRemoteInspector.SignatureEvidence(
                "unsigned", null, null);

        assertDoesNotThrow(() -> GitRemoteInspector.enforceSignature(
                signed, "required", Set.of("a".repeat(40))));
        assertThrows(IllegalStateException.class, () -> GitRemoteInspector.enforceSignature(
                signed, "required", Set.of("b".repeat(40))));
        assertDoesNotThrow(() -> GitRemoteInspector.enforceSignature(unsigned, "optional", Set.of()));
        assertThrows(IllegalStateException.class, () -> GitRemoteInspector.enforceSignature(
                unsigned, "required", Set.of("a".repeat(40))));
    }

    @Test
    void rejectsBlobAboveConfiguredBound(@TempDir Path directory) throws Exception {
        GitRemoteInspector bounded = new GitRemoteInspector(
                "github.com", 8, 8, 4096, name -> null);
        try (Git git = Git.init().setDirectory(directory.toFile()).call()) {
            Files.writeString(directory.resolve("job.py"), "x".repeat(4097), StandardCharsets.UTF_8);
            git.add().addFilepattern("job.py").call();
            var commit = git.commit().setMessage("job").setAuthor("Alice", "alice@example.test")
                    .setCommitter("Alice", "alice@example.test").call();
            var sourceRef = git.getRepository().updateRef("refs/aetheris/source");
            sourceRef.setNewObjectId(commit);
            sourceRef.update();

            assertThrows(IllegalArgumentException.class, () -> bounded.inspectRepository(
                    git.getRepository(), commit.name(), "job.py", "optional", Set.of()));
        }
    }
}
