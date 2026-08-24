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
 * <p><strong>Algorithm:</strong> scan the {@code X-Forwarded-For} entries right-to-left and
 * return the first one NOT inside the configured trusted-proxy CIDRs. Trusted = ranges the
 * in-cluster proxy can egress from; Traefik's own hop is the TCP peer, never an XFF entry.
 * This yields the real IP in both cases above and — critically — ignores anything a direct
 * caller spoofs to the left of the Traefik-appended entry. If every entry is trusted (or the
 * header is absent), fall back to the socket peer address.
 *
 * <p>Entries that are not IP literals are skipped (they can only occupy attacker-written
 * positions left of the Traefik-appended peer), so the resolved value is always a valid IP —
 * required by the {@code inet}-typed {@code contact_submissions.source_ip} column. The two
 * failure modes this design is tested against: all site users collapsing into one bucket
 * (the proxy egress), and a spoofed left-most entry letting a bot choose its bucket.
 *
 * <p>{@code server.forward-headers-strategy} is deliberately {@code none}: Spring's
 * {@code ForwardedHeaderFilter} trusts the <em>left-most</em> {@code X-Forwarded-For} entry
 * (client-spoofable) and strips the header before controllers see it — exactly the wrong
 * semantics here.
 *
 * <p>Trusted CIDRs are configurable ({@code allpets.http.trusted-proxy-cidrs} /
 * {@code ALLPETS_TRUSTED_PROXY_CIDRS}); the defaults cover every private/CGNAT/loopback
 * range, which subsumes the k3s pod (10.42/16), service (10.43/16), node-LAN (10.0.10.x)
 * and Tailscale (100.64/10) ranges the proxy can egress from — a real external client can
 * never legitimately carry a private source IP.
 */
@Component
public class ClientIpResolver {

    static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final List<Cidr> trustedProxyCidrs;

    public ClientIpResolver(
            @Value("${allpets.http.trusted-proxy-cidrs:"
                    + "10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,100.64.0.0/10,"
                    + "127.0.0.0/8,169.254.0.0/16,::1/128,fc00::/7,fe80::/10}")
            List<String> trustedProxyCidrs) {
        this.trustedProxyCidrs = trustedProxyCidrs.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Cidr::parse)
                .toList();
    }

    /**
     * @return the resolved client IP in canonical text form — the right-most untrusted
     *         {@code X-Forwarded-For} entry, or the socket peer when none qualifies
     */
    public String resolve(HttpServletRequest request) {
        List<String> chain = forwardedChain(request);
        for (int i = chain.size() - 1; i >= 0; i--) {
            InetAddress address = parseAddress(chain.get(i));
            if (address == null) {
                continue;   // not an IP literal — attacker-written filler, never Traefik's entry
            }
            if (!isTrusted(address)) {
                return address.getHostAddress();
            }
        }
        return request.getRemoteAddr();
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
     * Parses an XFF entry as an IP literal (never a DNS lookup), tolerating the
     * {@code ip:port} and {@code [ipv6]:port} forms some proxies emit. Returns {@code null}
     * for anything else.
     */
    private static InetAddress parseAddress(String entry) {
        String candidate = entry;
        if (candidate.startsWith("[")) {                       // [2001:db8::1]:443 or [2001:db8::1]
            int close = candidate.indexOf(']');
            if (close < 0) {
                return null;
            }
            candidate = candidate.substring(1, close);
        } else {
            int firstColon = candidate.indexOf(':');
            if (firstColon >= 0 && candidate.indexOf(':', firstColon + 1) < 0) {
                candidate = candidate.substring(0, firstColon);   // ipv4:port — one colon only
            }
        }
        try {
            return InetAddress.ofLiteral(candidate);
        } catch (IllegalArgumentException notAnIpLiteral) {
            return null;
        }
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
