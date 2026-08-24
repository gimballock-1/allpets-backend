package com.allpets.api.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Unit tests for the right-most-non-trusted {@code X-Forwarded-For} resolution (14.2).
 * The two failure modes that would break the per-IP limiter are pinned here: all proxied
 * visitors collapsing into the proxy-egress bucket, and a direct caller choosing its
 * bucket by spoofing left-most entries.
 */
class ClientIpResolverTest {

    /** Mirrors the production default: cluster/private ranges are proxy hops. */
    private final ClientIpResolver resolver = new ClientIpResolver(List.of(
            "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10",
            "127.0.0.0/8", "169.254.0.0/16", "::1/128", "fc00::/7", "fe80::/10"));

    private static MockHttpServletRequest request(String remoteAddr, String... xffValues) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/contact");
        request.setRemoteAddr(remoteAddr);
        for (String value : xffValues) {
            request.addHeader(ClientIpResolver.X_FORWARDED_FOR, value);
        }
        return request;
    }

    @Test
    void twoProxiedClientsResolveToTwoDifferentIps() {
        // The catastrophic failure mode: every site visitor arrives via the same in-cluster
        // proxy egress (10.42.x). Resolution must skip that hop and see each real client.
        String first = resolver.resolve(request("10.42.0.1", "203.0.113.7, 10.42.0.9"));
        String second = resolver.resolve(request("10.42.0.1", "198.51.100.8, 10.42.0.9"));

        assertThat(first).isEqualTo("203.0.113.7");
        assertThat(second).isEqualTo("198.51.100.8");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void spoofedLeftMostEntryDoesNotPickTheBucket() {
        // A direct bot sends its own XFF; Traefik appends the bot's real peer IP last.
        // The spoofed left entry must be ignored — the bot resolves to its real IP.
        String resolved = resolver.resolve(request("10.42.0.1", "203.0.113.7, 198.51.100.20"));

        assertThat(resolved).isEqualTo("198.51.100.20");
    }

    @Test
    void spoofedTrustedLeftEntryStillResolvesToRealPeer() {
        // Spoofing a cluster-internal address doesn't help either.
        String resolved = resolver.resolve(request("10.42.0.1", "10.42.0.9, 198.51.100.20"));

        assertThat(resolved).isEqualTo("198.51.100.20");
    }

    @Test
    void missingHeaderFallsBackToRemoteAddr() {
        assertThat(resolver.resolve(request("198.51.100.5"))).isEqualTo("198.51.100.5");
    }

    @Test
    void allTrustedEntriesFallBackToRemoteAddr() {
        String resolved = resolver.resolve(request("10.42.0.1", "10.42.0.9, 127.0.0.1"));

        assertThat(resolved).isEqualTo("10.42.0.1");
    }

    @Test
    void repeatedHeadersAreJoinedInOrder() {
        // Two physical X-Forwarded-For headers == one comma-joined chain.
        String resolved = resolver.resolve(request("10.42.0.1", "203.0.113.7", "10.42.0.9"));

        assertThat(resolved).isEqualTo("203.0.113.7");
    }

    @Test
    void ipv4WithPortIsStripped() {
        String resolved = resolver.resolve(request("10.42.0.1", "203.0.113.7:5555, 10.42.0.9"));

        assertThat(resolved).isEqualTo("203.0.113.7");
    }

    @Test
    void bracketedIpv6WithPortIsStripped() {
        String resolved = resolver.resolve(request("10.42.0.1", "[2001:db8::1]:443, 10.42.0.9"));

        assertThat(resolved).isEqualTo("2001:db8:0:0:0:0:0:1");
    }

    @Test
    void ipv6LoopbackAndUlaAreTrusted() {
        String resolved = resolver.resolve(request("10.42.0.1", "2001:db8::7, fd00::1, ::1"));

        assertThat(resolved).isEqualTo("2001:db8:0:0:0:0:0:7");
    }

    @Test
    void nonIpJunkEntriesAreSkipped() {
        // Junk can only occupy attacker-written positions; it must never become a bucket
        // key (source_ip is an inet column) nor mask the real untrusted entry.
        String resolved = resolver.resolve(request("10.42.0.1", "not-an-ip, 203.0.113.7, 10.42.0.9"));

        assertThat(resolved).isEqualTo("203.0.113.7");
    }

    @Test
    void junkOnlyChainFallsBackToRemoteAddr() {
        String resolved = resolver.resolve(request("10.42.0.1", "unknown, garbage"));

        assertThat(resolved).isEqualTo("10.42.0.1");
    }

    @Test
    void invalidCidrConfigFailsFast() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ClientIpResolver(List.of("10.0.0.0/33")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ClientIpResolver(List.of("not-a-cidr/8")));
    }

    @Test
    void bareIpInTrustedListMatchesExactly() {
        ClientIpResolver exact = new ClientIpResolver(List.of("10.42.0.9"));

        assertThat(exact.resolve(request("1.1.1.1", "203.0.113.7, 10.42.0.9")))
                .isEqualTo("203.0.113.7");
        assertThat(exact.resolve(request("1.1.1.1", "203.0.113.7, 10.42.0.10")))
                .isEqualTo("10.42.0.10");   // only .9 is trusted
    }
}
