package com.tvpirate.backend.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClientIpTest {

    @Test
    void ipv4IsTheAddressItself() {
        assertThat(ClientIp.networkKey("203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void ipv6CollapsesToItsSlash64() {
        String key = ClientIp.networkKey("2001:db8:abcd:12:1:2:3:4");

        assertThat(key).isEqualTo("2001:db8:abcd:12:0:0:0:0/64");
        // A VPS or household rotating through its own /64 still gets one bucket.
        assertThat(ClientIp.networkKey("2001:db8:abcd:12:ffff:ffff:ffff:ffff")).isEqualTo(key);
        assertThat(ClientIp.networkKey("2001:db8:abcd:13::1")).isNotEqualTo(key);
    }

    @Test
    void ipv4MappedIpv6BecomesPlainIpv4() {
        assertThat(ClientIp.networkKey("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void aZoneIdIsIgnored() {
        assertThat(ClientIp.networkKey("fe80::1%eth0")).isEqualTo("fe80:0:0:0:0:0:0:0/64");
    }
}
