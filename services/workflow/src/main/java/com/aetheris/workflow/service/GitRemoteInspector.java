package com.aetheris.workflow.service;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.lib.Ref;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Read-only Git transport with an explicit SSRF and credential boundary. */
@Component
public class GitRemoteInspector {

    private static final Pattern HOST = Pattern.compile("^[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?$");
    private static final Pattern PATH_SEGMENT = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final Pattern BRANCH = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]{0,127}$");
    private static final Pattern CREDENTIAL_REF = Pattern.compile(
            "^env://AETHERIS_GIT_CREDENTIAL_[A-Z0-9_]{1,64}$");

    private final Set<String> allowedHosts;
    private final int timeoutSeconds;
    private final Function<String, String> environment;

    @Autowired
    public GitRemoteInspector(
            @Value("${aetheris.git.allowed-hosts:github.com,gitee.com}") String allowedHosts,
            @Value("${aetheris.git.connect-timeout-seconds:8}") int timeoutSeconds) {
        this(allowedHosts, timeoutSeconds, System::getenv);
    }

    GitRemoteInspector(String allowedHosts, int timeoutSeconds, Function<String, String> environment) {
        this.allowedHosts = Arrays.stream(allowedHosts == null ? new String[0] : allowedHosts.split(","))
                .map(String::trim).map(value -> value.toLowerCase(Locale.ROOT))
                .filter(StringUtils::hasText).collect(Collectors.toUnmodifiableSet());
        if (this.allowedHosts.isEmpty() || this.allowedHosts.stream().anyMatch(value -> !HOST.matcher(value).matches())) {
            throw new IllegalStateException("Git 远端主机白名单配置不合法");
        }
        this.timeoutSeconds = Math.clamp(timeoutSeconds, 1, 30);
        this.environment = environment;
    }

    public RemoteHead inspect(String remoteUrl, String branch, String credentialUsername, String credentialRef) {
        String normalizedUrl = normalizeRemoteUrl(remoteUrl);
        String normalizedBranch = normalizeBranch(branch);
        LsRemoteCommand command = Git.lsRemoteRepository().setRemote(normalizedUrl)
                .setHeads(true).setTags(false).setTimeout(timeoutSeconds);
        if (StringUtils.hasText(credentialRef) || StringUtils.hasText(credentialUsername)) {
            String ref = credentialRef == null ? "" : credentialRef.trim();
            String username = credentialUsername == null ? "" : credentialUsername.trim();
            if (!CREDENTIAL_REF.matcher(ref).matches() || username.isEmpty() || username.length() > 128) {
                throw new IllegalArgumentException("Git 凭据必须使用专用环境变量引用并填写用户名");
            }
            String token = environment.apply(ref.substring("env://".length()));
            if (!StringUtils.hasText(token) || token.length() > 4096) {
                throw new IllegalStateException("Git 凭据引用未配置或长度不合法");
            }
            command.setCredentialsProvider(
                    new org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(username, token));
        }
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
            // Transport exceptions can contain URLs and credential context. Never return them.
            throw new IllegalStateException("Git 远端不可达或凭据无效");
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

    private static IllegalArgumentException invalidRemote() {
        return new IllegalArgumentException("Git 地址必须是白名单主机上的无凭据 HTTPS 仓库地址");
    }

    public record RemoteHead(String remoteUrl, String branch, String commitSha) { }
}
