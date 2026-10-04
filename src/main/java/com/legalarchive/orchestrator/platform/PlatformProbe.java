package com.legalarchive.orchestrator.platform;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.security.Security;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * What this instance thinks its host is: OS and JVM identity, where the configured paths really
 * point, which interpreters can be found, and whether the feed base directory is case-sensitive.
 * Spec: {@code .claude/LINUX_AND_GUI_CONFIG.md} section 7.
 *
 * <p><b>JDK only, no Spring.</b> Everything it needs is passed in, so it compiles with
 * {@code --release 8} and runs against a real file system in a test.
 *
 * <h3>A whitelist, by construction</h3>
 * The endpoint over this class is public until authentication exists. So it reads system
 * properties and environment variables only by literal name - the names in section 7.3 - and never
 * calls {@code System.getProperties()} or an argument-less {@code getenv()}. A build scan asserts
 * that. Two environment variables are read, {@code PATH} and {@code SystemRoot}; neither is
 * returned.
 *
 * <h3>Read-only, with one declared exception</h3>
 * Nothing is created, not even a missing directory: a missing directory is a finding. The
 * exception is {@link #caseSensitivity}, which writes one temporary file inside a directory that
 * already exists and is writable, and removes it.
 */
public final class PlatformProbe {

    /** {@code os.name} in production; set by tests to exercise the Windows search rules on Linux. */
    public String osName = System.getProperty("os.name");
    /** Working directory every relative path is resolved against. */
    public String userDir = System.getProperty("user.dir");
    /** Only for the Windows search order (the JVM's own directory comes first). Never returned. */
    public String javaHome = System.getProperty("java.home");
    /** Where workflow imports are staged; shown as a path row. */
    public String tmpDir = System.getProperty("java.io.tmpdir");
    /** Environment lookup; only ever asked for PATH and SystemRoot. */
    public Function<String, String> env = new Function<String, String>() {
        @Override public String apply(String name) { return System.getenv(name); }
    };
    /** Clock, injectable so the probe floor can be tested without waiting. */
    public LongSupplier clock = new LongSupplier() {
        @Override public long getAsLong() { return System.currentTimeMillis(); }
    };

    /** A case probe writes a file; never more often than this, whatever the caller asks. */
    public static final long PROBE_FLOOR_MS = 60_000L;

    static final String PROBE_PREFIX = ".op-caseprobe-";
    static final String PROBE_SUFFIX = ".tmp";

    private final Map<String, Map<String, Object>> probeCache = new LinkedHashMap<String, Map<String, Object>>();
    private final Map<String, Long> probeTime = new LinkedHashMap<String, Long>();
    private final SecureRandom random = new SecureRandom();

    // ------------------------------------------------------------------ report

    /** One configured path the report must describe. */
    public static final class PathSpec {
        public final String key;
        public final String configured;
        public final boolean directory;

        public PathSpec(String key, String configured, boolean directory) {
            this.key = key;
            this.configured = configured;
            this.directory = directory;
        }
    }

    /**
     * The whole response of {@code GET /api/platform}. A part that cannot be computed carries
     * its own {@code error} and the rest is still returned: a diagnostics page that dies on the
     * first unreadable directory is useless exactly when it is needed.
     *
     * @param interpreters key -> configured executable, in display order
     */
    public Map<String, Object> report(List<PathSpec> paths, Map<String, String> interpreters,
                                      String caseDir, boolean refreshProbe) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        try {
            out.put("system", system());
        } catch (RuntimeException e) {
            out.put("system", error(e));
        }
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        List<PathSpec> all = new ArrayList<PathSpec>(paths);
        all.add(new PathSpec("tmpDir", tmpDir, true));
        for (PathSpec ps : all) {
            try {
                rows.add(path(ps.key, ps.configured, ps.directory));
            } catch (RuntimeException e) {
                Map<String, Object> m = error(e);
                m.put("key", ps.key);
                rows.add(m);
            }
        }
        out.put("paths", rows);
        try {
            out.put("sunMscapi", Boolean.valueOf(sunMscapi()));
        } catch (RuntimeException e) {
            out.put("sunMscapi", error(e));
        }
        List<Map<String, Object>> exes = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, String> e : interpreters.entrySet()) {
            exes.add(interpreter(e.getKey(), e.getValue()));
        }
        out.put("interpreters", exes);
        try {
            out.put("caseSensitivity", caseSensitivity(caseDir, refreshProbe));
        } catch (RuntimeException e) {
            out.put("caseSensitivity", error(e));
        }
        return out;
    }

    private static Map<String, Object> error(Exception e) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("error", describe(e));
        return m;
    }

    // ------------------------------------------------------------------ system

    /** True for the Windows family - the same test {@code unarchive.HostRules.detect} applies. */
    public boolean windowsRules() {
        return osName != null && osName.trim().toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** Which executable search the interpreter rows used: the page states it (spec 7.9.6). */
    public String interpreterRules() {
        return windowsRules() ? "windows" : "path-only";
    }

    public Map<String, Object> system() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("osName", nz(osName));
        m.put("osArch", nz(System.getProperty("os.arch")));
        m.put("javaVersion", nz(System.getProperty("java.version")));
        m.put("javaVendor", nz(System.getProperty("java.vendor")));
        m.put("fileEncoding", nz(System.getProperty("file.encoding")));
        m.put("jnuEncoding", nz(System.getProperty("sun.jnu.encoding")));
        m.put("defaultCharset", Charset.defaultCharset().name());
        m.put("userDir", nz(userDir));
        m.put("interpreterRules", interpreterRules());
        return m;
    }

    public boolean sunMscapi() {
        return Security.getProvider("SunMSCAPI") != null;
    }

    // ------------------------------------------------------------------ paths

    /**
     * One path row. An EMPTY value is "not set" and is never resolved: {@code Paths.get("")} is
     * the working directory, so an unset optional directory would otherwise be shown as an
     * existing, writable one (spec 7.9.2).
     */
    public Map<String, Object> path(String key, String configured, boolean directory) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("key", key);
        m.put("configured", nz(configured));
        m.put("expected", directory ? "directory" : "file");
        if (configured == null || configured.trim().isEmpty()) {
            m.put("set", Boolean.FALSE);
            return m;
        }
        m.put("set", Boolean.TRUE);
        try {
            Path p = absolute(configured.trim());
            m.put("absolute", p.toString());
            boolean exists = Files.exists(p);
            m.put("exists", Boolean.valueOf(exists));
            if (exists) {
                boolean kindOk = directory ? Files.isDirectory(p) : Files.isRegularFile(p);
                m.put("kindOk", Boolean.valueOf(kindOk));
                m.put("writable", Boolean.valueOf(Files.isWritable(p)));
            } else if (!directory) {
                // A file that is legitimately absent on a new instance: can it be created?
                Path parent = p.getParent();
                m.put("creatable", Boolean.valueOf(parent != null && Files.isDirectory(parent)
                        && Files.isWritable(parent)));
            }
        } catch (RuntimeException e) {
            m.put("error", describe(e));
        }
        return m;
    }

    private Path absolute(String value) {
        Path p = Paths.get(value);
        if (!p.isAbsolute()) p = Paths.get(nz(userDir)).resolve(p);
        return p.toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------------ interpreters

    /**
     * Whether a configured interpreter can be found, WITHOUT executing it. So {@code FOUND} means
     * a file is there, not that it runs, and no version is reported.
     */
    public Map<String, Object> interpreter(String key, String configured) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("key", key);
        m.put("configured", nz(configured));
        m.put("rules", interpreterRules());
        m.put("resolved", "");
        m.put("how", "");
        String v = configured == null ? "" : configured.trim();
        if (v.isEmpty()) {
            m.put("status", "NOT_CONFIGURED");
            return m;
        }
        try {
            boolean win = windowsRules();
            boolean hasSeparator = v.indexOf('/') >= 0 || v.indexOf('\\') >= 0;
            boolean drive = win && v.length() >= 2 && v.charAt(1) == ':' && Character.isLetter(v.charAt(0));
            if (hasSeparator || drive) {
                boolean abs = Paths.get(v).isAbsolute() || (win && (v.startsWith("\\\\") || (drive && hasSeparator)));
                if (!abs) {
                    // Each step runs in its own working directory, and what a relative executable
                    // path is resolved against differs by OS. Not knowable here (spec 7.9.3).
                    m.put("status", "UNDETERMINED");
                    m.put("how", "relative path");
                    m.put("detail", "a relative path is resolved per step, against the step's working"
                            + " directory or the server's depending on the OS; use a bare name or an absolute path");
                    return m;
                }
                m.put("how", "absolute path");
                Path hit = candidate(Paths.get(v).getParent(), Paths.get(v).getFileName().toString(), win, m);
                finish(m, hit);
                return m;
            }
            m.put("how", "searched");
            Path hit = null;
            for (String dir : searchDirs(win)) {
                hit = candidate(Paths.get(dir), v, win, m);
                if (hit != null) break;
            }
            finish(m, hit);
        } catch (RuntimeException e) {
            m.put("status", "NOT_FOUND");
            m.put("detail", describe(e));
        }
        return m;
    }

    private static void finish(Map<String, Object> m, Path hit) {
        if (hit != null) {
            m.put("status", "FOUND");
            m.put("resolved", hit.toAbsolutePath().normalize().toString());
            m.remove("detail");
        } else {
            m.put("status", "NOT_FOUND");
        }
    }

    /**
     * The file a directory contributes for a name, or null. On Windows {@code .exe} is appended
     * to a name with no extension, as CreateProcess does - and PATHEXT is NOT consulted, so a
     * {@code foo.cmd} is not found by the name {@code foo}, here as there. Elsewhere the file
     * must be executable; one that exists but is not is remembered in {@code detail}.
     */
    private static Path candidate(Path dir, String name, boolean win, Map<String, Object> m) {
        if (dir == null) return null;
        String n = name;
        if (win && n.indexOf('.') < 0) n = n + ".exe";
        Path f;
        try {
            f = dir.resolve(n);
        } catch (RuntimeException e) {
            return null;
        }
        if (!Files.isRegularFile(f)) return null;
        if (!win && !Files.isExecutable(f)) {
            m.put("detail", "found " + f + " but it is not executable");
            return null;
        }
        return f;
    }

    /**
     * The directories the OS would search for a bare name, in its order. Windows: the JVM's own
     * directory, the working directory, System32, System, the Windows directory, then PATH -
     * the documented order of CreateProcess. Elsewhere: PATH, and when there is none the default
     * the JDK itself falls back to.
     */
    List<String> searchDirs(boolean win) {
        List<String> dirs = new ArrayList<String>();
        String path = env.apply("PATH");
        if (win) {
            if (javaHome != null && !javaHome.isEmpty()) dirs.add(javaHome + File.separator + "bin");
            if (userDir != null && !userDir.isEmpty()) dirs.add(userDir);
            String root = env.apply("SystemRoot");
            if (root != null && !root.trim().isEmpty()) {
                dirs.add(root + File.separator + "System32");
                dirs.add(root + File.separator + "System");
                dirs.add(root);
            }
            addAll(dirs, path, ';');
        } else {
            addAll(dirs, path == null ? "/bin:/usr/bin" : path, ':');
        }
        return dirs;
    }

    private static void addAll(List<String> dirs, String path, char sep) {
        if (path == null) return;
        int start = 0;
        for (int i = 0; i <= path.length(); i++) {
            if (i == path.length() || path.charAt(i) == sep) {
                String d = path.substring(start, i).trim();
                if (d.length() >= 2 && d.charAt(0) == '"' && d.charAt(d.length() - 1) == '"') {
                    d = d.substring(1, d.length() - 1);
                }
                if (!d.isEmpty()) dirs.add(d);
                start = i + 1;
            }
        }
    }

    // ------------------------------------------------------------------ case sensitivity

    /**
     * Case sensitivity of ONE directory: a temporary file is created, looked up under another
     * case, and deleted. The answer is about that directory, not about the server.
     *
     * <p>A result that involved a write is cached for the life of the JVM. {@code refresh} asks
     * for a new probe; it is honoured at most once per {@link #PROBE_FLOOR_MS}, because the
     * endpoint is public and this method writes.
     */
    public synchronized Map<String, Object> caseSensitivity(String configuredDir, boolean refresh) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("leftover", "");
        if (configuredDir == null || configuredDir.trim().isEmpty()) {
            return notDetermined(m, "", "directory is not set");
        }
        Path dir;
        try {
            dir = absolute(configuredDir.trim());
        } catch (RuntimeException e) {
            return notDetermined(m, configuredDir, describe(e));
        }
        String key = dir.toString();
        // Nothing is written in these three cases, so nothing is cached or throttled.
        if (!Files.exists(dir)) return notDetermined(m, key, "directory does not exist");
        if (!Files.isDirectory(dir)) return notDetermined(m, key, "not a directory");
        if (!Files.isWritable(dir)) return notDetermined(m, key, "directory is not writable");

        long now = clock.getAsLong();
        Map<String, Object> cached = probeCache.get(key);
        if (cached != null) {
            long age = now - probeTime.get(key).longValue();
            if (!refresh || age < PROBE_FLOOR_MS) {
                Map<String, Object> copy = new LinkedHashMap<String, Object>(cached);
                copy.put("cached", Boolean.TRUE);
                if (refresh) copy.put("throttled", Boolean.TRUE);
                return copy;
            }
        }
        m.put("directory", key);
        String name = PROBE_PREFIX + hex(8) + PROBE_SUFFIX;
        Path file = dir.resolve(name);
        boolean created = false;
        try {
            Files.createFile(file);                      // CREATE_NEW: never overwrites
            created = true;
            boolean seen = Files.exists(dir.resolve(name.toUpperCase(Locale.ROOT)));
            m.put("result", seen ? "CASE_INSENSITIVE" : "CASE_SENSITIVE");
        } catch (Exception e) {
            m.put("result", "NOT_DETERMINED");
            m.put("reason", "the probe file could not be created: " + describe(e));
        } finally {
            if (created) {
                try {
                    Files.delete(file);
                } catch (Exception e) {
                    m.put("leftover", name);
                }
            }
        }
        m.put("probedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date(now)));
        m.put("cached", Boolean.FALSE);
        probeCache.put(key, new LinkedHashMap<String, Object>(m));
        probeTime.put(key, Long.valueOf(now));
        return m;
    }

    private static Map<String, Object> notDetermined(Map<String, Object> m, String dir, String reason) {
        m.put("directory", dir);
        m.put("result", "NOT_DETERMINED");
        m.put("reason", reason);
        m.put("cached", Boolean.FALSE);
        return m;
    }

    private String hex(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (int i = 0; i < b.length; i++) {
            sb.append(Character.forDigit((b[i] >> 4) & 0xF, 16)).append(Character.forDigit(b[i] & 0xF, 16));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** Exception class plus message: enough to act on, and it is the caller's own input echoed. */
    private static String describe(Exception e) {
        String msg = e.getMessage();
        return e.getClass().getSimpleName() + (msg == null || msg.isEmpty() ? "" : ": " + msg);
    }
}
