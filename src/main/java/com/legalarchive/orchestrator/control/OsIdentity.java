package com.legalarchive.orchestrator.control;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Who a process or a file belongs to, as the operating system says it.
 *
 * <p>{@link #declared()} reads the identity of THIS process from its token, through
 * {@code com.sun.security.auth.module.NTSystem} on Windows and {@code UnixSystem} elsewhere -
 * never {@code user.name}, which {@code -Duser.name=} overrides. Reflection keeps the class
 * compilable against any JDK; both classes exist in Java 8 and are exported in 9+.
 *
 * <p>{@link #owner(Path)} is what the instance records as the user of a request: the OS sets
 * the owner of a file, a non-root user cannot change it on Linux, and on Windows only holders of
 * the take-ownership or restore privileges can (spec {@code .claude/HEADLESS.md} section 5.2).
 */
public final class OsIdentity {

    private OsIdentity() { }

    public static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** The identity of the running process: {@code DOMAIN\name} on Windows, {@code name} elsewhere; "" if unreadable. */
    public static String declared() {
        try {
            if (windows()) {
                Class<?> c = Class.forName("com.sun.security.auth.module.NTSystem");
                Object nt = c.getConstructor().newInstance();
                String domain = (String) call(c, nt, "getDomain");
                String name = (String) call(c, nt, "getName");
                if (name == null) return "";
                return (domain == null || domain.isEmpty()) ? name : domain + "\\" + name;
            }
            Class<?> c = Class.forName("com.sun.security.auth.module.UnixSystem");
            Object ux = c.getConstructor().newInstance();
            String name = (String) call(c, ux, "getUsername");
            return name == null ? "" : name;
        } catch (Throwable t) {
            return "";
        }
    }

    private static Object call(Class<?> c, Object o, String method) throws Exception {
        Method m = c.getMethod(method);
        return m.invoke(o);
    }

    /** The OS owner of a file, as its name. */
    public static String owner(Path file) throws IOException {
        return Files.getOwner(file).getName();
    }

    /**
     * Whether the declared identity names the same account as the owner. Windows account names
     * are case-insensitive; elsewhere they are compared exactly.
     */
    public static boolean sameAccount(String owner, String declared) {
        if (owner == null || declared == null) return false;
        return windows() ? owner.equalsIgnoreCase(declared) : owner.equals(declared);
    }
}
