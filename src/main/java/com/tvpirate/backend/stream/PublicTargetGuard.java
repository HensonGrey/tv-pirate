package com.tvpirate.backend.stream;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Blocks the stream proxy from being turned into an SSRF probe of our own
 * network. Providers are gray-market and their playlists are unvalidated
 * wire content — every URI in one becomes a server-side fetch, so a
 * compromised or MITM'd provider could point a segment/key URI at an
 * internal address (e.g. cloud metadata) and have this server fetch it on
 * the attacker's behalf. Checks the *resolved* address, not the URL text.
 */
@Component
public class PublicTargetGuard {

    /** DNS-cached per host for a minute — this runs per HLS segment, so
     * re-resolving on every request would add real latency across a
     * playback session. */
    private final Cache<String, Boolean> resolutionCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(1000)
            .build();

    /** Ranges Java's InetAddress doesn't already cover via isLoopbackAddress /
     * isSiteLocalAddress / isLinkLocalAddress / isAnyLocalAddress /
     * isMulticastAddress (those five give us RFC1918, loopback, link-local —
     * which covers the 169.254.169.254 cloud metadata IP — the wildcard
     * address, and multicast for free). */
    private static final List<Cidr> EXTRA_BLOCKED_IPV4 = List.of(
            Cidr.of("0.0.0.0", 8),        // "this network" (RFC 791)
            Cidr.of("100.64.0.0", 10),    // shared address space / CGNAT
            Cidr.of("192.0.0.0", 24),     // IETF protocol assignments
            Cidr.of("192.0.2.0", 24),     // TEST-NET-1
            Cidr.of("198.18.0.0", 15),    // benchmarking
            Cidr.of("198.51.100.0", 24),  // TEST-NET-2
            Cidr.of("203.0.113.0", 24),   // TEST-NET-3
            Cidr.of("240.0.0.0", 4));     // reserved

    /** Throws (fails closed) unless the URL is http(s) and every address the
     * host resolves to is public. Call this again at fetch time, not just
     * when a token is minted — a target that was public when registered can
     * point somewhere private by the time it's used. */
    public void assertPublicTarget(String rawUrl) {
        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "unparseable stream target");
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "blocked stream target scheme: " + scheme);
        }
        String host = uri.getHost();
        if (host == null || !isPublicHost(host)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "blocked stream target host: " + host);
        }
    }

    private boolean isPublicHost(String host) {
        return Boolean.TRUE.equals(resolutionCache.get(host, this::resolveAndCheck));
    }

    private Boolean resolveAndCheck(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            return addresses.length > 0 && Arrays.stream(addresses).noneMatch(PublicTargetGuard::isPrivate);
        } catch (UnknownHostException e) {
            return false; // unresolvable — fail closed
        }
    }

    private static boolean isPrivate(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                || address.isLinkLocalAddress() || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        return address instanceof Inet4Address ipv4
                && EXTRA_BLOCKED_IPV4.stream().anyMatch(cidr -> cidr.contains(ipv4));
    }

    private record Cidr(int network, int mask) {
        static Cidr of(String literalBase, int prefixLength) {
            int mask = prefixLength == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - prefixLength));
            return new Cidr(toInt(literalBase) & mask, mask);
        }

        boolean contains(Inet4Address address) {
            return (toInt(address) & mask) == network;
        }

        private static int toInt(Inet4Address address) {
            byte[] bytes = address.getAddress();
            return ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16)
                    | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
        }

        private static int toInt(String literal) {
            try {
                return toInt((Inet4Address) InetAddress.getByName(literal));
            } catch (UnknownHostException e) {
                throw new IllegalStateException("bad CIDR literal: " + literal, e);
            }
        }
    }
}
