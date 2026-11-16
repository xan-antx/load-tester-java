package com.elevate.analysis.target;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * An address block such as {@code 10.0.0.0/8} or {@code fc00::/7}.
 *
 * @param network the block's first address
 * @param prefix  number of leading bits that must match
 */
public record Cidr(InetAddress network, int prefix) {

    /**
     * Parses {@code "address/prefix"}. Only literal addresses are accepted, so no DNS lookup happens.
     *
     * @param text e.g. {@code 172.16.0.0/12}
     * @return the block
     */
    public static Cidr parse(String text) {
        int slash = text.indexOf('/');
        try {
            InetAddress network = literal(text.substring(0, slash));
            return new Cidr(network, Integer.parseInt(text.substring(slash + 1)));
        } catch (IllegalArgumentException | StringIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("not a CIDR block: " + text, e);
        }
    }

    /** Whether {@code address} lies inside this block (an IPv4 address never matches an IPv6 block). */
    public boolean contains(InetAddress address) {
        byte[] a = address.getAddress();
        byte[] n = network.getAddress();
        if (a.length != n.length) {
            return false;
        }
        int fullBytes = prefix / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (a[i] != n[i]) {
                return false;
            }
        }
        int remainingBits = prefix % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xFF << (8 - remainingBits) & 0xFF;
        return (a[fullBytes] & mask) == (n[fullBytes] & mask);
    }

    /**
     * Parses a literal IP address. {@link InetAddress#getByName} does no DNS lookup for a
     * literal, and it turns IPv4-mapped IPv6 addresses ({@code ::ffff:10.0.0.1}) into IPv4 ones.
     */
    static InetAddress literal(String text) {
        if (!text.matches("(\\d{1,3}\\.){3}\\d{1,3}|[0-9a-fA-F.]*:[0-9a-fA-F:.]*")) {
            throw new IllegalArgumentException("not an IP address: " + text);
        }
        try {
            return InetAddress.getByName(text);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(text, e);
        }
    }
}
