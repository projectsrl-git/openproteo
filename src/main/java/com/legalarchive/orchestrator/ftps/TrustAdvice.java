package com.legalarchive.orchestrator.ftps;

import java.security.Security;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What this server can do about trusting an FTPS server's certificate, for the targets page.
 *
 * <p>{@link TrustMode#WINDOWS} reads the operating system store through the SunMSCAPI provider,
 * which exists only on a Windows JVM. Elsewhere a target using it fails when it connects. That
 * must be visible on the page where the target is edited, not discovered by the first run.
 *
 * <h3>What is proposed, and what is never touched</h3>
 * Only a NEW target is proposed a mode: {@code WINDOWS} where the store exists, {@code JVM}
 * elsewhere. {@code JVM} and not {@code FILE} because on a Linux server the cacerts of a
 * distribution's Java is normally the system CA store (on Debian and Ubuntu it is a link to
 * {@code /etc/ssl/certs/java/cacerts} - checked), so it is the nearest thing to "what the
 * operating system trusts", and it needs no file placed by hand.
 *
 * <p>A SAVED target is never corrected, here or anywhere: one saved with {@code WINDOWS} loads,
 * saves and runs as {@code WINDOWS} on any host. And a target with NO trust mode at all - one
 * written by hand - still means {@code WINDOWS} on every host ({@link TrustMode#parse},
 * {@code FtpsTarget}): making an absent value follow the host would give one file two meanings.
 * On a host without the store it fails with a message that says what to choose.
 *
 * <p>JDK only.
 */
public final class TrustAdvice {

    private TrustAdvice() { }

    /** True when {@code trustMode=WINDOWS} can work on this JVM. */
    public static boolean windowsStoreAvailable() {
        return Security.getProvider("SunMSCAPI") != null;
    }

    /** The mode proposed for a NEW target. Never applied to a saved one. */
    public static TrustMode suggested(boolean windowsStoreAvailable) {
        return windowsStoreAvailable ? TrustMode.WINDOWS : TrustMode.JVM;
    }

    /** For {@code GET /api/ftp-targets/trust}. */
    public static Map<String, Object> describe() {
        return describe(windowsStoreAvailable());
    }

    static Map<String, Object> describe(boolean available) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("windowsAvailable", Boolean.valueOf(available));
        m.put("suggested", suggested(available).name());
        m.put("reason", available ? ""
                : "this Java runtime has no Windows certificate store (the SunMSCAPI provider exists only on Windows)");
        return m;
    }
}
