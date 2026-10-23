package com.aetheris.workflow.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/** Preflight capability boundary. Controlled DNS and network egress are still required against DNS rebinding. */
@Component
public final class OutboundTargetPolicy {
    static final String DEFAULT_NETWORKS = "127.0.0.0/8,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,::1/128,fc00::/7";
    private static final ExecutorService DNS = new ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "outbound-dns");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final List<Network> networks;
    private final Set<String> hosts;
    private final Resolver resolver;

    public OutboundTargetPolicy() { this(DEFAULT_NETWORKS, "", InetAddress::getAllByName); }

    @Autowired
    public OutboundTargetPolicy(
            @Value("${aetheris.outbound.allowed-cidrs:" + DEFAULT_NETWORKS + "}") String cidrs,
            @Value("${aetheris.outbound.allowed-hosts:}") String hosts) {
        this(cidrs, hosts, InetAddress::getAllByName);
    }

    OutboundTargetPolicy(String cidrs, String hosts, Resolver resolver) {
        this.networks = Arrays.stream(cidrs.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(Network::parse).toList();
        this.hosts = Arrays.stream(hosts.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(OutboundTargetPolicy::normalizeHost).collect(Collectors.toUnmodifiableSet());
        this.resolver = resolver;
    }

    public String httpOrigin(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                    || !(uri.getRawPath() == null || uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))) {
                throw new IllegalArgumentException("计算引擎地址必须是无凭据、无路径的 HTTP(S) 基础地址");
            }
            requireHost(uri.getHost());
            return new URI(uri.getScheme(), null, uri.getHost().toLowerCase(Locale.ROOT),
                    uri.getPort(), null, null, null).toASCIIString();
        } catch (java.net.URISyntaxException error) {
            throw new IllegalArgumentException("计算引擎地址格式不正确");
        }
    }

    public void jdbc(String jdbcUrl) {
        String network = jdbcUrl.substring("jdbc:".length());
        if (network.startsWith("h2:") || network.startsWith("derby:") || network.startsWith("sqlite:")) return;
        if (network.startsWith("oracle:thin:@//")) network = "oracle://" + network.substring("oracle:thin:@//".length());
        int boundary = network.indexOf(';');
        if (boundary >= 0) network = network.substring(0, boundary);
        URI uri = URI.create(network);
        requireHost(uri.getHost());
    }

    public void requireHost(String value) {
        String host = normalizeHost(value);
        Future<InetAddress[]> resolution;
        try { resolution = DNS.submit(() -> resolver.resolve(host)); }
        catch (RejectedExecutionException error) { throw new IllegalArgumentException("出站地址解析繁忙，请稍后重试"); }
        InetAddress[] addresses;
        try { addresses = resolution.get(2, TimeUnit.SECONDS); }
        catch (InterruptedException error) {
            resolution.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("出站地址解析已取消");
        } catch (ExecutionException | TimeoutException error) {
            resolution.cancel(true);
            throw new IllegalArgumentException("出站地址解析失败或超时");
        }
        if (addresses == null || addresses.length == 0 || addresses.length > 32) {
            throw new IllegalArgumentException("出站地址解析结果无效");
        }
        for (InetAddress address : addresses) {
            if (forbidden(address) || (!hosts.contains(host) && networks.stream().noneMatch(n -> n.contains(address)))) {
                throw new IllegalArgumentException("出站目标不在允许的业务网络内");
            }
        }
    }

    private static String normalizeHost(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("出站主机不能为空");
        String host = value.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.contains(":")) {
            if (!host.matches("[0-9a-f:]+")) throw new IllegalArgumentException("IPv6 地址格式不正确");
        } else {
            if (!host.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?") || host.contains("..")
                    || host.startsWith("0x") || host.matches("[0-9]+")) {
                throw new IllegalArgumentException("出站主机格式不正确");
            }
            if (host.matches("[0-9.]+")) {
                String[] octets = host.split("\\.", -1);
                if (octets.length != 4) throw new IllegalArgumentException("IPv4 地址必须使用四段十进制");
                for (String octet : octets) {
                    if (!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet) > 255)
                        throw new IllegalArgumentException("IPv4 地址格式不正确");
                }
            }
        }
        return host;
    }

    private static boolean forbidden(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        String canonical = address.getHostAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 255;
            return first == 0 || first >= 224 || "100.100.100.200".equals(canonical)
                    || "168.63.129.16".equals(canonical);
        }
        return bytes.length != 16 || "fd00:ec2:0:0:0:0:0:254".equals(canonical);
    }

    @FunctionalInterface interface Resolver { InetAddress[] resolve(String host) throws UnknownHostException; }

    private record Network(byte[] address, int bits) {
        static Network parse(String value) {
            try {
                String[] pieces = value.split("/", -1);
                if (pieces.length != 2 || !pieces[0].matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException();
                InetAddress address = InetAddress.getByName(normalizeHost(pieces[0]));
                int bits = Integer.parseInt(pieces[1]);
                if (bits < 0 || bits > address.getAddress().length * 8) throw new IllegalArgumentException();
                return new Network(address.getAddress(), bits);
            } catch (Exception error) { throw new IllegalArgumentException("出站 CIDR 配置无效：" + value); }
        }
        boolean contains(InetAddress candidate) {
            byte[] bytes = candidate.getAddress();
            if (bytes.length != address.length) return false;
            for (int i = 0; i < bits; i++) {
                int mask = 1 << (7 - i % 8);
                if ((bytes[i / 8] & mask) != (address[i / 8] & mask)) return false;
            }
            return true;
        }
    }
}
