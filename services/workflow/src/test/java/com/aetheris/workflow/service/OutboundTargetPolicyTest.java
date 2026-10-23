package com.aetheris.workflow.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetAddress;
import static org.junit.jupiter.api.Assertions.*;

class OutboundTargetPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"169.254.169.254", "100.100.100.200", "168.63.129.16", "0.0.0.0", "224.0.0.1", "255.255.255.255", "fe80::1", "fd00:ec2::254", "::", "ff02::1"})
    void forbiddenTargetsCannotBeAllowlisted(String address) {
        var policy = new OutboundTargetPolicy("0.0.0.0/0,::/0", "allowed.example", host -> InetAddress.getAllByName(address));
        assertThrows(IllegalArgumentException.class, () -> policy.requireHost("allowed.example"));
    }

    @Test
    void checksEveryResolvedAddressAndDoesNotTrustHostnameAlone() {
        var policy = new OutboundTargetPolicy(OutboundTargetPolicy.DEFAULT_NETWORKS, "", host -> new InetAddress[]{
                InetAddress.getByName("192.168.1.1"), InetAddress.getByName("169.254.169.254")});
        assertThrows(IllegalArgumentException.class, () -> policy.requireHost("business.example"));
    }

    @Test
    void privateBusinessTargetsAndExplicitPublicHostAreSupported() {
        var privatePolicy = new OutboundTargetPolicy(OutboundTargetPolicy.DEFAULT_NETWORKS, "", host -> InetAddress.getAllByName("192.168.1.10"));
        assertDoesNotThrow(() -> privatePolicy.requireHost("business.example"));
        assertEquals("http://business.example:8083", privatePolicy.httpOrigin("http://business.example:8083/"));
        var publicPolicy = new OutboundTargetPolicy("", "business.example", host -> InetAddress.getAllByName("203.0.113.10"));
        assertDoesNotThrow(() -> publicPolicy.requireHost("business.example"));
        assertThrows(IllegalArgumentException.class, () -> publicPolicy.requireHost("other.example"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://user:secret@localhost", "http://localhost/path", "http://localhost?x=1", "http://localhost#x", "file:///etc/passwd", "http://127.1", "http://0177.0.0.1", "http://2130706433", "http://[fe80::1%25eth0]", "http://localhost:0"})
    void ambiguousHttpOriginsAreRejected(String endpoint) {
        assertThrows(IllegalArgumentException.class, () -> new OutboundTargetPolicy().httpOrigin(endpoint));
    }

    @Test
    void jdbcUsesActualUrlRatherThanSeparateDisplayHost() {
        var policy = new OutboundTargetPolicy();
        assertThrows(IllegalArgumentException.class, () -> policy.jdbc("jdbc:postgresql://169.254.169.254:5432/x"));
        assertThrows(IllegalArgumentException.class, () -> policy.jdbc("jdbc:oracle:thin:@//169.254.169.254:1521/x"));
        assertThrows(IllegalArgumentException.class, () -> policy.jdbc("jdbc:sqlserver://169.254.169.254:1433;databaseName=x"));
        assertDoesNotThrow(() -> policy.jdbc("jdbc:postgresql://127.0.0.1:5432/x"));
    }
}
