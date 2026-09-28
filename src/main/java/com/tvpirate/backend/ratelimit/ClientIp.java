package com.tvpirate.backend.ratelimit;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The network key for anonymous limits. IPv4 is the address itself; IPv6 is
 * its /64, since one household or VPS owns the whole prefix. remoteAddr is
 * already the forwarded client when the peer is a trusted proxy
 * (server.forward-headers-strategy=native). vault:rate-limiting-deep-dive#client-ip
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String networkKey(HttpServletRequest request) {
        return networkKey(request.getRemoteAddr());
    }

    static String networkKey(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return "unknown";
        }
        try {
            // remoteAddr is always a literal, so this never does a DNS lookup;
            // IPv4-mapped IPv6 (::ffff:a.b.c.d) comes back as plain IPv4.
            InetAddress address = InetAddress.getByName(stripZone(remoteAddr));
            if (address instanceof Inet6Address) {
                byte[] prefix = Arrays.copyOf(address.getAddress(), 16);
                Arrays.fill(prefix, 8, 16, (byte) 0);
                return InetAddress.getByAddress(prefix).getHostAddress() + "/64";
            }
            return address.getHostAddress();
        } catch (UnknownHostException e) {
            return remoteAddr;
        }
    }

    /** fe80::1%eth0 → fe80::1 — the zone names an interface, not a network. */
    private static String stripZone(String address) {
        int zone = address.indexOf('%');
        return zone < 0 ? address : address.substring(0, zone);
    }
}
