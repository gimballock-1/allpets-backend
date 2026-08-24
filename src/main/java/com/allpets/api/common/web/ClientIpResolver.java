package com.allpets.api.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the real client IP behind the site's proxy chain (14.2).
 *
 * <p><strong>Topology</strong> (Frontend LLD §4.2): the browser POSTs the site's same-origin
 * {@code /api/contact}; the Next.js route handler server-side fetches this API and sets
 * {@code X-Forwarded-For: <real client IP>}; Traefik then appends its immediate peer (the
 * proxy's cluster-internal egress IP). So Spring sees {@code XFF: <client>, <proxy-egress>}
 * for proxied traffic, and {@code XFF: [<spoofed…>,] <bot-ip>} for a direct bot hit (Traefik
 * always appends the connecting peer as the last entry).
 *
 * <p><strong>Algorithm:</strong>
 * <ol>
 *   <li>If the socket peer ({@code getRemoteAddr()}) is NOT in the trusted-proxy set, the
 *       request did not come through a known proxy — the {@code X-Forwarded-For} header is
 *       ignored entirely (any client can write it) and the peer itself is the client.</li>
 *   <li>Otherwise scan the XFF entries right-to-left and return the first one outside the
 *       trusted set. Trusted = ranges a proxy hop's connection can originate from; Traefik's
 *       own hop is the TCP peer, never an XFF entry. This yields the real IP for both
 *       proxied visitors and direct bots, and ignores anything a caller spoofs to the left
 *       of the Traefik-appended entry — a bot cannot choose its rate-limit bucket.</li>
 *   <li>If every entry is trusted or unusable (or the header is absent), fall back to the
 *       socket peer — such traffic shares the proxy-hop bucket. That is deliberate: it only
 *       happens for cluster-internal callers or a proxy that failed to forward a client IP,
 *       never for internet traffic (Traefik always appends the external peer).</li>
 * </ol>
 *
 * <p>At most {@link #MAX_EXAMINED_ENTRIES} entries are examined (from the right), so an
 * adversarial mega-chain cannot force unbounded work. Entries that are not clean IP
 * literals — including junk-suffixed forms like {@code 1.2.3.4:garbage} or
 * {@code [2001:db8::1]junk} — are skipped; they can only occupy attacker-written positions
 * left of the Traefik-appended peer. The resolved value is therefore always a valid IP,
 * which the {@code inet}-typed {@code contact_submissions.source_ip} column requires.
 *
 * <p>{@code server.forward-headers-strategy} is deliberately {@code none}: Spring's
 * {@code ForwardedHeaderFilter} trusts the <em>left-most</em> {@code X-Forwarded-For} entry
 * (client-spoofable) and strips the header before controllers see it — exactly the wrong
 * semantics here.
 *
 * <p><strong>Trusted set</strong> ({@code allpets.http.trusted-proxy-cidrs} /
 * {@code ALLPETS_TRUSTED_PROXY_CIDRS}) is deliberately minimal — only source ranges a real
 * proxy hop occupies, verified against the quasar host 2026-08-23:
 * <ul>
 *   <li>{@code 10.42.0.0/16} — k3s pod CIDR (Traefik and the site proxy are pods here;
 *       this node's range is 10.42.0.0/24, the /16 is the k3s cluster default);</li>
 *   <li>{@code 10.0.10.113/32} — the node's exact LAN address (hairpin/SNAT egress);</li>
 *   <li>{@code 100.108.60.90/32} — the node's exact Tailscale address (NOT the whole
 *       100.64.0.0/10 CGNAT block: any other tailnet device is an ordinary client);</li>
 *   <li>loopback — unreachable from any network; covers port-forward/local dev.</li>
 * </ul>
 * Broad ranges (blanket RFC1918, 100.64.0.0/10, ULA) are deliberately NOT trusted: traffic
 * arriving from LAN/Tailscale/tunnel paths must bucket as itself, not have its spoofable
 * XFF honoured. If the cluster ranges change, the property is the escape hatch.
 */
@Component
public class ClientIpResolver {

    static final String X_FORWARDED_FOR = "X-Forwarded-For";

    /** Upper bound on XFF entries examined (right-to-left) before falling back to the peer. */
    static final int MAX_EXAMINED_ENTRIES = 32;

    private final List<Cidr> trustedProxyCidrs;

    public ClientIpResolver(
            @Value("${allpets.http.trusted-proxy-cidrs:"
                    + "10.42.0.0/16,10.0.10.113/32,100.108.60.90/32,127.0.0.1/32,::1/128}")
            List<String> trustedProxyCidrs) {
        this.trustedProxyCidrs = trustedProxyCidrs.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Cidr::parse)
                .toList();
    }

    /**
     * @return the resolved client IP in canonical text form — the right-most non-trusted
     *         {@code X-Forwarded-For} entry when the socket peer is a trusted proxy hop,
     *         otherwise the socket peer itself
     */
    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        InetAddress peer = parseAddress(remoteAddr);
        if (peer == null || !isTrusted(peer)) {
            // Not a known proxy hop: the header is attacker-writable, the peer is the client.
            return peer == null ? remoteAddr : peer.getHostAddress();
        }

        List<String> chain = forwardedChain(request);
        int floor = Math.max(0, chain.size() - MAX_EXAMINED_ENTRIES);
        for (int i = chain.size() - 1; i >= floor; i--) {
            InetAddress address = parseAddress(chain.get(i));
            if (address == null) {
                continue;   // junk — attacker-written filler, never Traefik's appended entry
            }
            if (!isTrusted(address)) {
                return address.getHostAddress();
            }
        }
        return remoteAddr;   // shared proxy-hop bucket; see class javadoc, step 3
    }

    /** All XFF entries in order, joined across repeated headers, split on commas, trimmed. */
    private static List<String> forwardedChain(HttpServletRequest request) {
        List<String> chain = new ArrayList<>();
        Enumeration<String> headers = request.getHeaders(X_FORWARDED_FOR);
        while (headers != null && headers.hasMoreElements()) {
            for (String entry : headers.nextElement().split(",")) {
                String trimmed = entry.trim();
                if (!trimmed.isEmpty()) {
                    chain.add(trimmed);
                }
            }
        }
        return chain;
    }

    private boolean isTrusted(InetAddress address) {
        for (Cidr cidr : trustedProxyCidrs) {
            if (cidr.contains(address)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses an XFF entry as an IP literal (never a DNS lookup), tolerating exactly the
     * {@code ipv4:port} and {@code [ipv6]:port} forms some proxies emit. Anything else —
     * including junk-suffixed forms like {@code 1.2.3.4:garbage} or {@code [::1]junk} —
     * returns {@code null} and is skipped rather than partially salvaged.
     */
    private static InetAddress parseAddress(String entry) {
        if (entry == null || entry.isEmpty()) {
            return null;
        }
        String candidate = entry;
        if (candidate.startsWith("[")) {                       // [2001:db8::1] or [2001:db8::1]:443
            int close = candidate.indexOf(']');
            if (close < 0 || !isEmptyOrPortSuffix(candidate.substring(close + 1))) {
                return null;
            }
            candidate = candidate.substring(1, close);
        } else {
            int firstColon = candidate.indexOf(':');
            if (firstColon >= 0 && candidate.indexOf(':', firstColon + 1) < 0) {
                if (!isPort(candidate.substring(firstColon + 1))) {
                    return null;                               // "1.2.3.4:garbage" is junk
                }
                candidate = candidate.substring(0, firstColon);   // ipv4:port — one colon only
            }
        }
        try {
            return InetAddress.ofLiteral(candidate);
        } catch (IllegalArgumentException notAnIpLiteral) {
            return null;
        }
    }

    private static boolean isEmptyOrPortSuffix(String s) {
        return s.isEmpty() || (s.charAt(0) == ':' && isPort(s.substring(1)));
    }

    private static boolean isPort(String s) {
        if (s.isEmpty() || s.length() > 5) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** A parsed {@code network/prefix} range; matches by address family + leading prefix bits. */
    record Cidr(byte[] network, int prefixBits) {

        static Cidr parse(String cidr) {
            int slash = cidr.indexOf('/');
            String ipPart = slash < 0 ? cidr : cidr.substring(0, slash);
            InetAddress network;
            try {
                network = InetAddress.ofLiteral(ipPart);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("invalid trusted-proxy CIDR: " + cidr, e);
            }
            int maxBits = network.getAddress().length * 8;
            int prefix = maxBits;                          // bare IP == /32 or /128
            if (slash >= 0) {
                try {
                    prefix = Integer.parseInt(cidr.substring(slash + 1));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("invalid trusted-proxy CIDR: " + cidr, e);
                }
                if (prefix < 0 || prefix > maxBits) {
                    throw new IllegalArgumentException("invalid trusted-proxy CIDR: " + cidr);
                }
            }
            return new Cidr(network.getAddress(), prefix);
        }

        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) {
                return false;                              // different address family
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            int remainderBits = prefixBits % 8;
            if (remainderBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainderBits);
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
