package com.legalarchive.orchestrator.web;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/**
 * What the loopback GUI accepts (spec {@code .claude/HEADLESS.md} section 4.4, Q11 answered by the
 * author 2026-11-13). Pure: no servlet API, so the rules are tested on their own.
 *
 * <ul>
 *   <li><b>Host</b> must name the loopback ({@code 127.0.0.1}, {@code localhost}, {@code [::1]}, with
 *       any port). A page that rebinds its own domain to 127.0.0.1 sends ITS domain here, and is
 *       refused: DNS rebinding cannot read anything.</li>
 *   <li>A request that can change something (anything but GET, HEAD, OPTIONS) whose {@code Origin} -
 *       or, without one, {@code Referer} - names another origin is refused: a cross-site form cannot
 *       start a run. {@code Origin: null} counts as another origin.</li>
 *   <li>A request with neither header passes: {@code curl}, and older browsers that send neither on
 *       a same-site form, must keep working. A cross-site request from a modern browser carries one.</li>
 * </ul>
 */
public final class LoopbackRules {

    private LoopbackRules() { }

    /** Null when the request may pass; otherwise the reason it is refused. */
    public static String refuse(String method, String host, String origin, String referer) {
        String h = hostName(host);
        if (!isLoopbackName(h)) {
            return "this page answers only to 127.0.0.1 or localhost, not to '" + (host == null ? "" : host) + "'";
        }
        String m = method == null ? "" : method.toUpperCase(Locale.ROOT);
        if ("GET".equals(m) || "HEAD".equals(m) || "OPTIONS".equals(m)) return null;
        String claimed = origin != null ? origin : referer;
        if (claimed == null || claimed.trim().isEmpty()) return null;
        if (!sameOrigin(claimed.trim(), host)) {
            return "a change requested from another site (" + claimed.trim() + ") is refused";
        }
        return null;
    }

    /** The host part of a Host header, lower-case, without port or IPv6 brackets. */
    static String hostName(String host) {
        if (host == null) return "";
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end > 0 ? h.substring(1, end) : h;
        }
        int colon = h.indexOf(':');
        return colon >= 0 ? h.substring(0, colon) : h;
    }

    static boolean isLoopbackName(String h) {
        return "127.0.0.1".equals(h) || "localhost".equals(h) || "::1".equals(h)
                || "0:0:0:0:0:0:0:1".equals(h);
    }

    /** Whether an Origin or Referer URL names the same host and port as the Host header. */
    static boolean sameOrigin(String url, String host) {
        if ("null".equals(url)) return false;
        try {
            URI u = new URI(url);
            if (u.getHost() == null) return false;
            String uh = u.getHost().toLowerCase(Locale.ROOT);
            if (uh.startsWith("[") && uh.endsWith("]")) uh = uh.substring(1, uh.length() - 1);
            int up = u.getPort() >= 0 ? u.getPort() : ("https".equalsIgnoreCase(u.getScheme()) ? 443 : 80);
            String hh = hostName(host);
            int hp = port(host, u.getScheme());
            return uh.equals(hh) && up == hp;
        } catch (Exception e) {
            return false;
        }
    }

    private static int port(String host, String scheme) {
        String h = host == null ? "" : host.trim();
        int close = h.lastIndexOf(']');
        int colon = h.lastIndexOf(':');
        if (colon > close && colon >= 0) {
            try { return Integer.parseInt(h.substring(colon + 1)); } catch (NumberFormatException e) { return -1; }
        }
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    /** Whether a configured bind address is a loopback address: the filter applies only then. */
    public static boolean isLoopbackAddress(String address) {
        if (address == null || address.trim().isEmpty()) return false;
        try {
            return InetAddress.getByName(address.trim()).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }
}
