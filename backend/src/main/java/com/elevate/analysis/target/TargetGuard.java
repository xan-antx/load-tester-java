package com.elevate.analysis.target;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Decides which hosts this server may send requests to.
 *
 * <p>A load tester that requests any URL a user types is a server-side request forgery
 * (SSRF) tool: pointed at {@code 10.0.0.5}, {@code localhost} or the cloud metadata address
 * it would probe the network the backend runs in. So before every probe, every redirect
 * hop and every load test the host is resolved and each of its addresses is checked by
 * {@link AddressClassifier}. Cloud metadata endpoints are always blocked; private, loopback,
 * link-local and multicast addresses only when {@code ALLOW_PRIVATE_TARGETS=false}.
 *
 * <p>A host that does not resolve is let through: there is nothing to connect to, and the
 * request then fails as "unreachable" on its own.
 */
public final class TargetGuard {

    private static final Set<String> METADATA_HOSTNAMES = Set.of("metadata.google.internal", "metadata", "instance-data");
    private static final String PRIVATE_HINT = " Private, loopback, link-local and multicast targets are blocked on this "
            + "server (set ALLOW_PRIVATE_TARGETS=true to allow them).";

    /** Looks host names up; replaceable so tests need no DNS. */
    @FunctionalInterface
    public interface HostResolver {
        /**
         * @param host a host name or literal address
         * @return its addresses
         * @throws UnknownHostException when it does not resolve
         */
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final boolean allowPrivate;
    private final HostResolver resolver;

    /**
     * @param allowPrivate whether private, loopback, link-local and multicast targets are allowed
     * @param resolver     DNS lookup
     */
    public TargetGuard(boolean allowPrivate, HostResolver resolver) {
        this.allowPrivate = allowPrivate;
        this.resolver = resolver;
    }

    /** A guard that uses the system resolver. */
    public TargetGuard(boolean allowPrivate) {
        this(allowPrivate, InetAddress::getAllByName);
    }

    /**
     * @param url the URL about to be requested
     * @return a plain-English reason when it must not be requested, else empty
     */
    public Optional<String> check(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return Optional.of("the URL has no host name");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return Optional.of("the URL has no host name");
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        String bare = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        if (METADATA_HOSTNAMES.contains(bare)) {
            return Optional.of("'" + host + "' is a cloud metadata host name, which is always blocked");
        }
        if (uri.getPort() > 65_535) {
            return Optional.of("the URL has an invalid port");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
        for (InetAddress address : addresses) {
            Optional<String> problem = AddressClassifier.problem(address, allowPrivate);
            if (problem.isPresent()) {
                String hint = problem.get().equals("a cloud metadata address") ? "" : PRIVATE_HINT;
                return Optional.of("'" + host + "' resolves to " + address.getHostAddress() + ", "
                        + problem.get() + "." + hint);
            }
        }
        return Optional.empty();
    }
}
