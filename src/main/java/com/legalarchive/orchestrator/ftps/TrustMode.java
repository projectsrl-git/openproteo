package com.legalarchive.orchestrator.ftps;

/** How the server's certificate is judged. */
public enum TrustMode {

    /**
     * The operating system trust store, read through KeyStore.getInstance("Windows-ROOT").
     * The default, and the reason is measured: the client in service reaches this estate without
     * ever prompting to accept the server certificate, which means Windows already trusts the
     * issuing CA - almost certainly an internal one distributed by group policy, since the host
     * sits inside an AD domain. Java does not read that store, it reads cacerts, where such a CA
     * is not present. This mode makes the JVM trust exactly what the working client trusts.
     */
    WINDOWS,

    /** The JVM cacerts. Correct for a server whose certificate chains to a public CA. */
    JVM,

    /** An explicit truststore file. */
    FILE,

    /**
     * Any certificate, unconditionally. It exists because it may be the only way to get a first
     * delivery through, and every run that uses it says so in the step log.
     */
    ANY;

    /**
     * An ABSENT value means WINDOWS, on every host - deliberately not "whatever suits this host".
     * A target file would otherwise mean one thing on Windows and another on Linux. Where the
     * Windows store does not exist the target fails on connect and says what to choose; only a
     * NEW target, in the page, is proposed a mode by host (see TrustAdvice).
     */
    public static TrustMode parse(String s) {
        if (s == null || s.trim().isEmpty()) {
            return WINDOWS;
        }
        return valueOf(s.trim().toUpperCase());
    }
}
