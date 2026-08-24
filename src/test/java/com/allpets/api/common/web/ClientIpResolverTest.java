package com.allpets.api.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Unit tests for client-IP resolution (14.2): XFF honoured only when the TCP peer is a
 * trusted proxy hop, then right-most non-trusted entry wins. The failure modes that would
 * break the per-IP limiter are pinned here: all proxied visitors collapsing into the
 * proxy-egress bucket, a caller choosing its bucket by spoofing XFF (from outside OR
 * through Traefik), junk-suffixed entries, and adversarial mega-chains.
 */
class ClientIpResolverTest {

    /** Traefik's pod address — the TCP peer Spring sees in production (verified on quasar). */
    private static final String TRAEFIK_PEER = "10.42.0.187";
    /** The Next.js site proxy pod — the XFF hop Traefik appends for proxied visitors. */
    private static final String PROXY_EGRESS = "10.42.0.188";

    /** Mirrors the production default: minimal, exact proxy-hop ranges only. */
    private final ClientIpResolver resolver = new ClientIpResolver(List.of(
            "10.42.0.0/16", "10.0.10.113/32", "100.108.60.90/32", "127.0.0.1/32", "::1/128"));

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
        // proxy egress. Resolution must skip that hop and see each real client.
        String first = resolver.resolve(request(TRAEFIK_PEER, "203.0.113.7, " + PROXY_EGRESS));
        String second = resolver.resolve(request(TRAEFIK_PEER, "198.51.100.8, " + PROXY_EGRESS));

        assertThat(first).isEqualTo("203.0.113.7");
        assertThat(second).isEqualTo("198.51.100.8");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void spoofedLeftMostEntryDoesNotPickTheBucket() {
        // A direct bot sends its own XFF through Traefik; Traefik appends the bot's real
        // peer IP last. The spoofed left entry must be ignored.
        String resolved = resolver.resolve(request(TRAEFIK_PEER, "203.0.113.7, 198.51.100.20"));

        assertThat(resolved).isEqualTo("198.51.100.20");
    }

    @Test
    void spoofedTrustedLeftEntryStillResolvesToRealPeer() {
        // Spoofing a cluster-internal address doesn't help either.
        String resolved = resolver.resolve(request(TRAEFIK_PEER, PROXY_EGRESS + ", 198.51.100.20"));

        assertThat(resolved).isEqualTo("198.51.100.20");
    }

    @Test
    void directSingleEntryXffResolvesToThatEntry() {
        // Direct bot with no spoofed prefix: Traefik appends just the bot's IP.
        String resolved = resolver.resolve(request(TRAEFIK_PEER, "198.51.100.20"));

        assertThat(resolved).isEqualTo("198.51.100.20");
    }

    @Test
    void untrustedPeerHasItsXffIgnoredEntirely() {
        // The peer is NOT a known proxy hop (a LAN host, a tailnet device, anything that
        // reached the pod without going through Traefik): its XFF is attacker-writable and
        // must be ignored wholesale — the peer itself is the client.
        assertThat(resolver.resolve(request("192.168.1.50", "1.2.3.4")))
                .isEqualTo("192.168.1.50");
        // A tailnet CLIENT (not the node's own 100.108.60.90) is an ordinary caller.
        assertThat(resolver.resolve(request("100.64.1.2", "1.2.3.4")))
                .isEqualTo("100.64.1.2");
    }

    @Test
    void tailscaleTrustIsTheNodeExactAddressNotTheCgnatBlock() {
        // The node's own Tailscale address is a legitimate hairpin hop…
        assertThat(resolver.resolve(request(TRAEFIK_PEER, "203.0.113.7, 100.108.60.90")))
                .isEqualTo("203.0.113.7");
        // …but any other CGNAT address is a client and becomes the bucket itself.
        assertThat(resolver.resolve(request(TRAEFIK_PEER, "203.0.113.7, 100.64.1.2")))
                .isEqualTo("100.64.1.2");
    }

    @Test
    void missingHeaderFallsBackToRemoteAddr() {
        assertThat(resolver.resolve(request("198.51.100.5"))).isEqualTo("198.51.100.5");
        assertThat(resolver.resolve(request(TRAEFIK_PEER))).isEqualTo(TRAEFIK_PEER);
    }

    @Test
    void allTrustedEntriesFallBackToRemoteAddr() {
        // Documented shared-bucket fallback: only cluster-internal traffic (or a proxy
        // that forwarded no client IP) can land here — never internet traffic, because
        // Traefik always appends the external peer as an untrusted entry.
        String resolved = resolver.resolve(request(TRAEFIK_PEER, PROXY_EGRESS + ", 127.0.0.1"));

        assertThat(resolved).isEqualTo(TRAEFIK_PEER);
    }

    @Test
    void repeatedHeadersAreJoinedInOrder() {
        // Two physical X-Forwarded-For headers == one comma-joined chain.
        String resolved = resolver.resolve(request(TRAEFIK_PEER, "203.0.113.7", PROXY_EGRESS));

        assertThat(resolved).isEqualTo("203.0.113.7");
    }

    @Test
    void ipv4WithPortIsStripped() {
        String resolved = resolver.resolve(request(TRAEFIK_PEER, "203.0.113.7:5555, " + PROXY_EGRESS));

        assertThat(resolved).isEqualTo("203.0.113.7");
    }

    @Test
    void bracketedIpv6WithPortIsStripped() {
        String resolved = resolver.resolve(request(TRAEFIK_PEER, "[2001:db8::1]:443, " + PROXY_EGRESS));

        assertThat(resolved).isEqualTo("2001:db8:0:0:0:0:0:1");
    }

    @Test
    void junkSuffixedEntriesAreRejectedNotSalvaged() {
        // "1.2.3.4:garbage" / "[2001:db8::1]junk" must be skipped as junk, not blindly
        // stripped into a usable address an attacker effectively chose.
        assertThat(resolver.resolve(request(TRAEFIK_PEER, "1.2.3.4:garbage")))
                .isEqualTo(TRAEFIK_PEER);
        assertThat(resolver.resolve(request(TRAEFIK_PEER, "[2001:db8::1]junk")))
                .isEqualTo(TRAEFIK_PEER);
        assertThat(resolver.resolve(request(TRAEFIK_PEER, "[2001:db8::1")))
                .isEqualTo(TRAEFIK_PEER);
    }

    @Test
    void nonIpJunkEntriesAreSkipped() {
        // Junk can only occupy attacker-written positions; it must never become a bucket
        // key (source_ip is an inet column) nor mask the real untrusted entry.
        String resolved = resolver.resolve(
                request(TRAEFIK_PEER, "not-an-ip, 203.0.113.7, " + PROXY_EGRESS));

        assertThat(resolved).isEqualTo("203.0.113.7");
    }

    @Test
    void junkOnlyChainFallsBackToRemoteAddr() {
        String resolved = resolver.resolve(request(TRAEFIK_PEER, "unknown, garbage"));

        assertThat(resolved).isEqualTo(TRAEFIK_PEER);
    }

    @Test
    void oversizedChainExaminesOnlyTheTail() {
        // An adversarial mega-chain: an untrusted entry buried beyond the examination
        // bound is never reached (bounded work), while one inside the tail window is.
        String manyTrusted = String.join(", ",
                Collections.nCopies(ClientIpResolver.MAX_EXAMINED_ENTRIES, PROXY_EGRESS));

        String buried = resolver.resolve(request(TRAEFIK_PEER, "8.8.8.8, " + manyTrusted));
        assertThat(buried).isEqualTo(TRAEFIK_PEER);   // fell back — bound respected

        String withinTail = resolver.resolve(request(TRAEFIK_PEER,
                "8.8.8.8, 198.51.100.20, " + String.join(", ",
                        Collections.nCopies(ClientIpResolver.MAX_EXAMINED_ENTRIES - 1, PROXY_EGRESS))));
        assertThat(withinTail).isEqualTo("198.51.100.20");
    }

    @Test
    void ipv6LoopbackIsTrustedUlaIsNot() {
        // ::1 (loopback hop) is skipped; a ULA address is NOT a known proxy hop and
        // becomes the bucket itself under the minimal trust set.
        assertThat(resolver.resolve(request(TRAEFIK_PEER, "2001:db8::7, ::1")))
                .isEqualTo("2001:db8:0:0:0:0:0:7");
        assertThat(resolver.resolve(request(TRAEFIK_PEER, "2001:db8::7, fd00::1")))
                .isEqualTo("fd00:0:0:0:0:0:0:1");
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

        // Peer must itself be the trusted hop for XFF to be honoured at all…
        assertThat(exact.resolve(request("10.42.0.9", "203.0.113.7, 10.42.0.9")))
                .isEqualTo("203.0.113.7");
        // …and the bare-IP trust is exact: .10 is not covered.
        assertThat(exact.resolve(request("10.42.0.9", "203.0.113.7, 10.42.0.10")))
                .isEqualTo("10.42.0.10");
        assertThat(exact.resolve(request("10.42.0.10", "203.0.113.7")))
                .isEqualTo("10.42.0.10");
    }
}
