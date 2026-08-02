package com.legalarchive.orchestrator.config;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Build identity read once from the filtered {@code build-info.properties}.
 *
 * <p>Maven substitutes the placeholders at package time (see the &lt;resources&gt; block in pom.xml);
 * the deploy scripts pass {@code -Dgit.commit} and {@code -Dbuild.number}. A build made without those
 * properties, or without filtering at all, leaves the raw {@code @token@} behind: those values are
 * treated as absent so the UI simply shows less rather than something meaningless.</p>
 */
public final class BuildInfo {

    private static volatile Map<String, Object> MAP;

    private BuildInfo() { }

    /** A raw {@code @placeholder@} (or {@code ${...}}) means the build did not substitute it. */
    private static String clean(String v) {
        if (v == null) return "";
        v = v.trim();
        if (v.startsWith("@") || v.startsWith("${")) return "";
        return v;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    public static Map<String, Object> map() {
        Map<String, Object> m = MAP;
        if (m != null) return m;

        Properties pr = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream("/build-info.properties")) {
            if (in != null) pr.load(in);
        } catch (Exception ignored) { }

        String ver = clean(pr.getProperty("build.version", ""));
        String num = clean(pr.getProperty("build.number", ""));
        String commit = clean(pr.getProperty("build.commit", ""));
        String time = clean(pr.getProperty("build.time", ""));
        if ("unknown".equals(commit)) commit = "";
        if ("0".equals(num)) num = "";
        if (time.isEmpty()) {                                   // fallback: when the WAR was assembled
            try {
                java.net.URL u = BuildInfo.class.getResource("/build-info.properties");
                long lm = (u == null) ? 0L : u.openConnection().getLastModified();
                if (lm > 0L) {
                    time = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(lm));
                }
            } catch (Exception ignored) { }
        }
        String shortCommit = commit.length() > 7 ? commit.substring(0, 7) : commit;
        String full = ver + (num.isEmpty() ? "" : ("." + num));

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("version", ver);
        out.put("buildNumber", num);
        out.put("commit", commit);
        out.put("shortCommit", shortCommit);
        out.put("buildTime", time);
        out.put("display", full);
        out.put("label", full + (shortCommit.isEmpty() ? "" : (" \u00B7 " + shortCommit)));
        MAP = out;
        return out;
    }

    /**
     * Short token appended to every static asset URL so a new build is never served from the browser
     * cache. Uses build number + commit; on a build without them it falls back to the build time, and
     * finally to a per-JVM value so a developer restart still refreshes.
     */
    public static String id() {
        Map<String, Object> m = map();
        String num = str(m.get("buildNumber")), sc = str(m.get("shortCommit"));
        String id = num + ((num.isEmpty() || sc.isEmpty()) ? "" : "-") + sc;
        if (id.isEmpty()) id = str(m.get("buildTime")).replaceAll("[^0-9]", "");
        if (id.isEmpty()) id = "dev" + Long.toString(START, 36);
        return id;
    }

    private static final long START = System.currentTimeMillis();
}
