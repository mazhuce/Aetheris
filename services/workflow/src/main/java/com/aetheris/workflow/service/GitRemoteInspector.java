package com.aetheris.workflow.service;

import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.GpgConfig;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.SignatureVerifier.SignatureVerification;
import org.eclipse.jgit.lib.SignatureVerifiers;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Read-only Git transport with explicit SSRF, credential, blob and signature boundaries. */
@Component
public class GitRemoteInspector {

    private static final Logger log = LoggerFactory.getLogger(GitRemoteInspector.class);
    private static final Pattern HOST = Pattern.compile("^[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?$");
    private static final Pattern PATH_SEGMENT = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final Pattern SOURCE_PATH = Pattern.compile("^[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*\\.(?:py|jar)$");
    private static final Pattern BRANCH = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]{0,127}$");
    private static final Pattern COMMIT = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern FINGERPRINT = Pattern.compile("^(?:[0-9a-f]{40}|[0-9a-f]{64})$");
    private static final Pattern CREDENTIAL_REF = Pattern.compile(
            "^env://AETHERIS_GIT_CREDENTIAL_[A-Z0-9_]{1,64}$");

    private final Set<String> allowedHosts;
    private final int timeoutSeconds;
    private final int fetchDepth;
    private final int maxBlobBytes;
    private final Function<String, String> environment;

    @Autowired
    public GitRemoteInspector(
            @Value("${aetheris.git.allowed-hosts:github.com,gitee.com}") String allowedHosts,
            @Value("${aetheris.git.connect-timeout-seconds:8}") int timeoutSeconds,
            @Value("${aetheris.git.fetch-depth:64}") int fetchDepth,
            @Value("${aetheris.git.max-blob-bytes:1048576}") int maxBlobBytes) {
        this(allowedHosts, timeoutSeconds, fetchDepth, maxBlobBytes, System::getenv);
    }

    GitRemoteInspector(String allowedHosts, int timeoutSeconds, Function<String, String> environment) {
        this(allowedHosts, timeoutSeconds, 64, 1024 * 1024, environment);
    }

    GitRemoteInspector(String allowedHosts, int timeoutSeconds, int fetchDepth, int maxBlobBytes,
                       Function<String, String> environment) {
        this.allowedHosts = Arrays.stream(allowedHosts == null ? new String[0] : allowedHosts.split(","))
                .map(String::trim).map(value -> value.toLowerCase(Locale.ROOT))
                .filter(StringUtils::hasText).collect(Collectors.toUnmodifiableSet());
        if (this.allowedHosts.isEmpty() || this.allowedHosts.stream().anyMatch(value -> !HOST.matcher(value).matches())) {
            throw new IllegalStateException("Git 远端主机白名单配置不合法");
        }
        this.timeoutSeconds = Math.clamp(timeoutSeconds, 1, 30);
        this.fetchDepth = Math.clamp(fetchDepth, 1, 512);
        this.maxBlobBytes = Math.clamp(maxBlobBytes, 4096, 8 * 1024 * 1024);
        this.environment = environment;
    }

    public RemoteHead inspect(String remoteUrl, String branch, String credentialUsername, String credentialRef) {
        String normalizedUrl = normalizeRemoteUrl(remoteUrl);
        String normalizedBranch = normalizeBranch(branch);
        LsRemoteCommand command = Git.lsRemoteRepository().setRemote(normalizedUrl)
                .setHeads(true).setTags(false).setTimeout(timeoutSeconds);
        CredentialsProvider credentials = credentials(credentialUsername, credentialRef);
        if (credentials != null) command.setCredentialsProvider(credentials);
        try {
            Map<String, Ref> refs = command.callAsMap();
            Ref head = refs.get("refs/heads/" + normalizedBranch);
            if (head == null || head.getObjectId() == null) {
                throw new IllegalArgumentException("Git 默认分支不存在");
            }
            return new RemoteHead(normalizedUrl, normalizedBranch, head.getObjectId().name());
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Git 远端不可达或凭据无效");
        }
    }

    public RevisionEvidence inspectRevision(RemoteHead head, String credentialUsername,
                                            String credentialRef, String signaturePolicy,
                                            Set<String> trustedFingerprints) {
        return inspectFetched(head, null, credentialUsername, credentialRef,
                signaturePolicy, trustedFingerprints).revision();
    }

    public SourceEvidence inspectSource(String remoteUrl, String branch, String commitSha,
                                        String sourcePath, String credentialUsername,
                                        String credentialRef, String signaturePolicy,
                                        Set<String> trustedFingerprints) {
        String normalizedUrl = normalizeRemoteUrl(remoteUrl);
        String normalizedBranch = normalizeBranch(branch);
        String commit = normalizeCommit(commitSha);
        String path = normalizeSourcePath(sourcePath);
        return inspectFetched(new RemoteHead(normalizedUrl, normalizedBranch, commit), path,
                credentialUsername, credentialRef, signaturePolicy, trustedFingerprints);
    }

    private SourceEvidence inspectFetched(RemoteHead head, String sourcePath,
                                          String credentialUsername, String credentialRef,
                                          String signaturePolicy, Set<String> trustedFingerprints) {
        Path temporary = null;
        try {
            temporary = Files.createTempDirectory("aetheris-git-");
            Path gitDirectory = temporary.resolve("source.git");
            try (Repository repository = FileRepositoryBuilder.create(gitDirectory.toFile())) {
                repository.create(true);
                FetchCommand fetch = Git.wrap(repository).fetch()
                        .setRemote(head.remoteUrl())
                        .setRefSpecs(new RefSpec("+refs/heads/" + head.branch() + ":refs/aetheris/source"))
                        .setTagOpt(TagOpt.NO_TAGS)
                        .setDepth(fetchDepth)
                        .setCheckFetchedObjects(true)
                        .setTimeout(timeoutSeconds);
                CredentialsProvider credentials = credentials(credentialUsername, credentialRef);
                if (credentials != null) fetch.setCredentialsProvider(credentials);
                fetch.call();
                return inspectRepository(repository, head.commitSha(), sourcePath,
                        signaturePolicy, trustedFingerprints);
            }
        } catch (IllegalArgumentException | IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            log.debug("Git source verification failed with {}", error.getClass().getSimpleName());
            throw new IllegalStateException("Git 源码读取或签名验证失败");
        } finally {
            deleteTemporary(temporary);
        }
    }

    SourceEvidence inspectRepository(Repository repository, String commitSha, String sourcePath,
                                     String signaturePolicy, Set<String> trustedFingerprints) {
        String commitId = normalizeCommit(commitSha);
        try (RevWalk revisions = new RevWalk(repository)) {
            ObjectId tipId = repository.resolve("refs/aetheris/source");
            ObjectId requestedId = ObjectId.fromString(commitId);
            if (tipId == null || !repository.getObjectDatabase().has(requestedId)) {
                throw new IllegalStateException("Git 修订不在允许的分支历史深度内");
            }
            RevCommit tip = revisions.parseCommit(tipId);
            RevCommit commit = revisions.parseCommit(requestedId);
            if (!revisions.isMergedInto(commit, tip)) {
                throw new IllegalStateException("Git 修订不属于当前允许分支");
            }
            SignatureEvidence signature = verifySignature(repository, commit);
            enforceSignature(signature, signaturePolicy, trustedFingerprints);
            RevisionEvidence revision = new RevisionEvidence(commitId, signature.status(),
                    signature.fingerprint(), signature.createdAt());
            if (sourcePath == null) return new SourceEvidence(revision, null, null, 0);

            String path = normalizeSourcePath(sourcePath);
            try (TreeWalk entry = TreeWalk.forPath(repository, path, commit.getTree())) {
                if (entry == null) throw new IllegalArgumentException("Git 修订中不存在指定源码文件");
                FileMode mode = entry.getFileMode(0);
                if (!FileMode.REGULAR_FILE.equals(mode) && !FileMode.EXECUTABLE_FILE.equals(mode)) {
                    throw new IllegalArgumentException("Git 源码必须是普通文件，不能是链接或子模块");
                }
                ObjectId blobId = entry.getObjectId(0);
                ObjectLoader loader = repository.open(blobId, Constants.OBJ_BLOB);
                long size = loader.getSize();
                if (size < 1 || size > maxBlobBytes) {
                    throw new IllegalArgumentException("Git 源码文件大小超出安全上限");
                }
                byte[] content = loader.getCachedBytes(maxBlobBytes);
                return new SourceEvidence(revision, blobId.name(), sha256(content), size);
            }
        } catch (IllegalArgumentException | IllegalStateException error) {
            throw error;
        } catch (IOException error) {
            throw new IllegalStateException("Git 修订或源码对象无法读取");
        }
    }

    SignatureEvidence verifySignature(Repository repository, RevCommit commit) {
        if (commit.getRawGpgSignature() == null) {
            return new SignatureEvidence("unsigned", null, null);
        }
        try {
            SignatureVerification verified = SignatureVerifiers.verify(
                    repository, new GpgConfig(repository.getConfig()), commit);
            String fingerprint = verified == null || verified.keyFingerprint() == null ? null
                    : verified.keyFingerprint().toLowerCase(Locale.ROOT);
            if (verified == null || !verified.verified() || verified.expired()
                    || fingerprint == null || !FINGERPRINT.matcher(fingerprint).matches()) {
                throw new IllegalStateException("Git commit 签名无效、已过期或缺少完整指纹");
            }
            OffsetDateTime createdAt = verified.creationDate() == null ? null
                    : verified.creationDate().toInstant().atOffset(ZoneOffset.UTC);
            return new SignatureEvidence("verified", fingerprint, createdAt);
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Git commit 签名无法使用已配置公钥验证");
        }
    }

    static void enforceSignature(SignatureEvidence signature, String policy,
                                 Set<String> trustedFingerprints) {
        String normalizedPolicy = policy == null ? "required" : policy.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("required", "optional").contains(normalizedPolicy)) {
            throw new IllegalArgumentException("Git 签名策略不合法");
        }
        Set<String> trusted = trustedFingerprints == null ? Set.of() : trustedFingerprints.stream()
                .map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
                .filter(StringUtils::hasText).collect(Collectors.toUnmodifiableSet());
        if ("unsigned".equals(signature.status())) {
            if ("required".equals(normalizedPolicy)) {
                throw new IllegalStateException("Git 仓库要求可信签名，但 commit 未签名");
            }
            return;
        }
        if (!"verified".equals(signature.status()) || signature.fingerprint() == null) {
            throw new IllegalStateException("Git commit 签名未通过验证");
        }
        if (!trusted.isEmpty() && !trusted.contains(signature.fingerprint())) {
            throw new IllegalStateException("Git commit 签名者不在仓库可信指纹列表中");
        }
        if ("required".equals(normalizedPolicy) && trusted.isEmpty()) {
            throw new IllegalStateException("强制签名仓库必须配置至少一个可信签名指纹");
        }
    }

    String normalizeRemoteUrl(String value) {
        try {
            URI uri = new URI(value == null ? "" : value.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String rawPath = uri.getRawPath();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !allowedHosts.contains(host)
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || rawPath == null || rawPath.length() < 4 || rawPath.length() > 400
                    || rawPath.contains("%") || rawPath.contains("\\") || rawPath.contains("//")) {
                throw invalidRemote();
            }
            String path = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
            if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            String withoutSuffix = path.endsWith(".git") ? path.substring(0, path.length() - 4) : path;
            String[] segments = withoutSuffix.split("/");
            if (segments.length < 2 || Arrays.stream(segments)
                    .anyMatch(segment -> !PATH_SEGMENT.matcher(segment).matches()
                            || ".".equals(segment) || "..".equals(segment))) {
                throw invalidRemote();
            }
            return new URI("https", null, host, uri.getPort(), "/" + withoutSuffix + ".git",
                    null, null).toString();
        } catch (URISyntaxException | IllegalArgumentException error) {
            throw invalidRemote();
        }
    }

    static String normalizeBranch(String value) {
        String branch = value == null ? "" : value.trim();
        if (!BRANCH.matcher(branch).matches() || branch.contains("..") || branch.contains("//")
                || branch.endsWith("/") || branch.endsWith(".") || branch.endsWith(".lock")
                || branch.contains("@{") || branch.contains("\\")
                || Arrays.stream(branch.split("/", -1))
                        .anyMatch(segment -> ".".equals(segment) || "..".equals(segment))) {
            throw new IllegalArgumentException("Git 分支名称不合法");
        }
        return branch;
    }

    private static String normalizeCommit(String value) {
        String commit = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!COMMIT.matcher(commit).matches()) throw new IllegalArgumentException("Git commit SHA 不合法");
        return commit;
    }

    private static String normalizeSourcePath(String value) {
        String path = value == null ? "" : value.trim();
        if (!SOURCE_PATH.matcher(path).matches() || path.contains("\\")
                || Arrays.stream(path.split("/", -1)).anyMatch(part -> ".".equals(part) || "..".equals(part))) {
            throw new IllegalArgumentException("Git 源码路径不合法");
        }
        return path;
    }

    private CredentialsProvider credentials(String credentialUsername, String credentialRef) {
        if (!StringUtils.hasText(credentialRef) && !StringUtils.hasText(credentialUsername)) return null;
        String ref = credentialRef == null ? "" : credentialRef.trim();
        String username = credentialUsername == null ? "" : credentialUsername.trim();
        if (!CREDENTIAL_REF.matcher(ref).matches() || username.isEmpty() || username.length() > 128) {
            throw new IllegalArgumentException("Git 凭据必须使用专用环境变量引用并填写用户名");
        }
        String token = environment.apply(ref.substring("env://".length()));
        if (!StringUtils.hasText(token) || token.length() > 4096) {
            throw new IllegalStateException("Git 凭据引用未配置或长度不合法");
        }
        return new UsernamePasswordCredentialsProvider(username, token);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static void deleteTemporary(Path directory) {
        if (directory == null) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException error) {
            log.warn("Could not remove temporary Git verification directory");
        }
    }

    private static IllegalArgumentException invalidRemote() {
        return new IllegalArgumentException("Git 地址必须是白名单主机上的无凭据 HTTPS 仓库地址");
    }

    public record RemoteHead(String remoteUrl, String branch, String commitSha) { }
    public record SignatureEvidence(String status, String fingerprint, OffsetDateTime createdAt) { }
    public record RevisionEvidence(String commitSha, String signatureStatus,
                                   String signerFingerprint, OffsetDateTime signatureCreatedAt) { }
    public record SourceEvidence(RevisionEvidence revision, String blobId,
                                 String blobSha256, long blobSize) { }
}
