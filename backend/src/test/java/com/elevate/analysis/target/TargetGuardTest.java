package com.elevate.analysis.target;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class TargetGuardTest {

    /** A resolver that maps every host to one fixed address, so tests need no DNS. */
    private static TargetGuard.HostResolver resolvingTo(String address) {
        return host -> new InetAddress[] {Cidr.literal(address)};
    }

    @ParameterizedTest
    @CsvSource({
        "127.0.0.1, a loopback address",
        "10.1.2.3, a private address",
        "172.16.0.9, a private address",
        "192.168.1.1, a private address",
        "100.64.0.1, a private address",
        "169.254.10.10, a link-local address",
        "224.0.0.1, a multicast address",
        "0.0.0.0, an unspecified address",
        "240.0.0.1, a reserved address",
        "::1, a loopback address",
        "fc00::1, a private address",
        "fe80::1, a link-local address",
    })
    void blocksNonPublicAddressesWhenPrivateTargetsAreOff(String address, String reason) {
        TargetGuard guard = new TargetGuard(false, resolvingTo(address));
        assertThat(guard.check("http://target.example/api")).hasValueSatisfying(problem -> {
            assertThat(problem).contains(reason).contains("ALLOW_PRIVATE_TARGETS=true");
        });
    }

    @Test
    void allowsPublicAddresses() {
        assertThat(new TargetGuard(false, resolvingTo("93.184.216.34")).check("https://example.com/")).isEmpty();
    }

    @Test
    void allowsPrivateAddressesWhenConfigured() {
        assertThat(new TargetGuard(true, resolvingTo("127.0.0.1")).check("http://localhost:8000/api/login")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"169.254.169.254", "169.254.170.2", "100.100.100.200", "168.63.129.16", "fd00:ec2::254"})
    void alwaysBlocksCloudMetadataAddresses(String address) {
        TargetGuard guard = new TargetGuard(true, resolvingTo(address));
        assertThat(guard.check("http://anything/")).hasValueSatisfying(problem ->
                assertThat(problem).contains("a cloud metadata address").doesNotContain("ALLOW_PRIVATE_TARGETS"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://metadata.google.internal/computeMetadata", "http://metadata/", "http://instance-data./x"})
    void alwaysBlocksMetadataHostNames(String url) {
        assertThat(new TargetGuard(true, resolvingTo("8.8.8.8")).check(url))
                .hasValueSatisfying(p -> assertThat(p).contains("cloud metadata host name"));
    }

    @Test
    void ipv4MappedIpv6IsCheckedAsIpv4() {
        TargetGuard guard = new TargetGuard(false, resolvingTo("::ffff:10.0.0.5"));
        assertThat(guard.check("http://[::ffff:10.0.0.5]/")).hasValueSatisfying(p -> assertThat(p).contains("private"));
    }

    @Test
    void unresolvableHostsAreLetThrough() {
        TargetGuard guard = new TargetGuard(false, host -> {
            throw new UnknownHostException(host);
        });
        assertThat(guard.check("http://does-not-exist.invalid/")).isEmpty();
    }

    @Test
    void urlsWithoutHostOrWithBadPortAreRefused() {
        TargetGuard guard = new TargetGuard(true, resolvingTo("8.8.8.8"));
        assertThat(guard.check("not a url")).hasValue("the URL has no host name");
        assertThat(guard.check("http:///path")).hasValue("the URL has no host name");
        assertThat(guard.check("http://example.com:99999/")).hasValue("the URL has an invalid port");
    }

    @Test
    void cidrMatchesPrefixesExactly() {
        Cidr block = Cidr.parse("172.16.0.0/12");
        assertThat(block.contains(Cidr.literal("172.31.255.255"))).isTrue();
        assertThat(block.contains(Cidr.literal("172.32.0.0"))).isFalse();
        assertThat(block.contains(Cidr.literal("::1"))).isFalse();
        assertThat(Cidr.parse("10.0.0.0/8").contains(Cidr.literal("10.200.1.1"))).isTrue();
    }
}
