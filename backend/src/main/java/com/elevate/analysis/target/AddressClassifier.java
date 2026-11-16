package com.elevate.analysis.target;

import java.net.InetAddress;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Says why an IP address must not be load-tested, in the same order and words as the
 * Python guard: cloud metadata first (always blocked), then — only when private targets
 * are not allowed — loopback, link-local, multicast, unspecified, private and reserved.
 *
 * <p>IPv4-mapped IPv6 addresses ({@code ::ffff:10.0.0.1}) need no special case: the JDK
 * already returns them as {@link java.net.Inet4Address}.
 */
public final class AddressClassifier {

    /** Cloud instance-metadata and platform endpoints (AWS, ECS, GCP, Azure, Alibaba). */
    static final Set<InetAddress> METADATA = Set.of(
            Cidr.literal("169.254.169.254"), Cidr.literal("169.254.170.2"), Cidr.literal("169.254.170.23"),
            Cidr.literal("100.100.100.200"), Cidr.literal("168.63.129.16"),
            Cidr.literal("fd00:ec2::254"), Cidr.literal("fd00:ec2::23"));

    /** Private-use blocks (Python's {@code is_private}) plus shared address space 100.64.0.0/10. */
    private static final List<Cidr> PRIVATE = List.of(
            Cidr.parse("0.0.0.0/8"), Cidr.parse("10.0.0.0/8"), Cidr.parse("100.64.0.0/10"),
            Cidr.parse("172.16.0.0/12"), Cidr.parse("192.0.0.0/24"), Cidr.parse("192.0.2.0/24"),
            Cidr.parse("192.168.0.0/16"), Cidr.parse("198.18.0.0/15"), Cidr.parse("198.51.100.0/24"),
            Cidr.parse("203.0.113.0/24"), Cidr.parse("fc00::/7"), Cidr.parse("2001:db8::/32"),
            Cidr.parse("100::/64"), Cidr.parse("2001::/23"));

    /** Reserved blocks: 240.0.0.0/4 (including the broadcast address). */
    private static final List<Cidr> RESERVED = List.of(Cidr.parse("240.0.0.0/4"));

    private AddressClassifier() {
    }

    /**
     * @param ip           a resolved address
     * @param allowPrivate whether private targets are allowed on this server
     * @return the reason it is blocked, e.g. {@code a loopback address}, or empty when allowed
     */
    public static Optional<String> problem(InetAddress ip, boolean allowPrivate) {
        if (METADATA.contains(ip)) {
            return Optional.of("a cloud metadata address");
        }
        if (allowPrivate) {
            return Optional.empty();
        }
        if (ip.isLoopbackAddress()) {
            return Optional.of("a loopback address");
        }
        if (ip.isLinkLocalAddress()) {
            return Optional.of("a link-local address");
        }
        if (ip.isMulticastAddress()) {
            return Optional.of("a multicast address");
        }
        if (ip.isAnyLocalAddress()) {
            return Optional.of("an unspecified address");
        }
        if (ip.isSiteLocalAddress() || PRIVATE.stream().anyMatch(c -> c.contains(ip))) {
            return Optional.of("a private address");
        }
        if (RESERVED.stream().anyMatch(c -> c.contains(ip))) {
            return Optional.of("a reserved address");
        }
        return Optional.empty();
    }
}
