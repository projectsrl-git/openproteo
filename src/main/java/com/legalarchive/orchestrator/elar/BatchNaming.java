package com.legalarchive.orchestrator.elar;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Names each INDX/PULL pair, reproducing the legacy scheme exactly.
 *
 * Two things about that scheme are worth stating, because neither is obvious from a filename:
 *
 * <p>The {@code C152100} segment is a <b>synthetic clock</b>, not a timestamp and not a sequence. It
 * starts at {@code <family>.output.start_time} or, when that is not set, at the wall-clock time the
 * run began, and advances by exactly sixty seconds per batch. Two runs of the same feed on the same
 * day with the same explicit {@code start_time} therefore produce colliding filenames; with it unset
 * they produce different ones, so nothing is overwritten but duplicates accumulate silently. That is
 * the real risk, and {@link #countSameDayPairs} exists to report it rather than to prevent it.
 *
 * <p>{@code D26229} is {@code String.format("%02d%03d", year % 100, dayOfYear)} - built with
 * {@code getDayOfYear()} and a format string, never with a {@code DateTimeFormatter} pattern. In
 * java.time {@code DD} is the day of the YEAR, which is exactly the trap that took down the validate
 * executor in production; keeping the arithmetic explicit means the pattern is never written at all.
 */
public final class BatchNaming {

    private final String indexPattern;
    private final String pullPattern;
    private final int filesPerJulianDate;
    private final int year;

    private int julianDay;
    private int fileCountToday = 0;
    private int totalSeconds;

    /**
     * @param julianDateStart the day of the year to start at, or 0 for today
     * @param startTime       HHmmss to start the clock at, or null for the current wall-clock time
     */
    public BatchNaming(String indexPattern, String pullPattern, int filesPerJulianDate,
                       int julianDateStart, String startTime, LocalDateTime now) {
        if (indexPattern == null || indexPattern.trim().isEmpty()) {
            throw new IllegalArgumentException("output.index_name_pattern is required");
        }
        if (pullPattern == null || pullPattern.trim().isEmpty()) {
            throw new IllegalArgumentException("output.pull_name_pattern is required");
        }
        this.indexPattern = indexPattern.trim();
        this.pullPattern = pullPattern.trim();
        this.filesPerJulianDate = filesPerJulianDate > 0 ? filesPerJulianDate : Integer.MAX_VALUE;
        this.year = now.getYear();
        this.julianDay = julianDateStart > 0 ? julianDateStart : now.getDayOfYear();
        this.totalSeconds = (startTime == null || startTime.trim().isEmpty())
                ? (now.getHour() * 3600 + now.getMinute() * 60 + now.getSecond())
                : parseHHmmss(startTime.trim());
    }

    static int parseHHmmss(String s) {
        if (s.length() != 6) {
            throw new IllegalArgumentException("output.start_time must be exactly HHmmss, got '" + s + "'");
        }
        try {
            int h = Integer.parseInt(s.substring(0, 2));
            int m = Integer.parseInt(s.substring(2, 4));
            int sec = Integer.parseInt(s.substring(4, 6));
            if (h > 23 || m > 59 || sec > 59) {
                throw new IllegalArgumentException("output.start_time '" + s + "' is not a valid time of day");
            }
            return h * 3600 + m * 60 + sec;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("output.start_time must be exactly HHmmss, got '" + s + "'");
        }
    }

    /** The names of one pair. */
    public static final class Pair {
        public final String indexName;      // the index filename with its extension removed
        public final String indexFileName;
        public final String pullFileName;
        Pair(String n, String i, String p) { indexName = n; indexFileName = i; pullFileName = p; }
    }

    /** Advances the clock and produces the next pair. */
    public Pair next() {
        if (fileCountToday >= filesPerJulianDate) {
            fileCountToday = 0;
            julianDay++;
            // the clock restarts with the day, as legacy does
            totalSeconds = totalSeconds % 86400;
        }
        String aajjj = String.format("%02d%03d", year % 100, julianDay);
        String hhmmss = hhmmss(totalSeconds);
        String idxFile = indexPattern.replace("[AAJJJ]", aajjj).replace("[HHMMSS]", hhmmss);
        String pullFile = pullPattern.replace("[AAJJJ]", aajjj).replace("[HHMMSS]", hhmmss);
        String idxName = stripExtension(idxFile);
        fileCountToday++;
        totalSeconds += 60;
        return new Pair(idxName, idxFile, pullFile);
    }

    static String hhmmss(int totalSeconds) {
        int s = ((totalSeconds % 86400) + 86400) % 86400;      // wrap past midnight rather than overflow
        return String.format("%02d%02d%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    /** {@code x.INDX.C152100.xml} -> {@code x.INDX.C152100}; the legacy {@code replace(".xml","")}. */
    static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * How many pairs for the current Julian day already sit in the output directory.
     *
     * Reported, not prevented. With {@code julian_date_start} and {@code start_time} commented out -
     * which is how the live configuration ships - a re-run produces new filenames rather than
     * overwriting, so nothing is lost but duplicate deliverables accumulate with nothing to say so.
     * Refusing a same-day re-run would break the case where a re-run is most needed, immediately
     * after a partial failure; saying how many are already there costs one directory listing.
     */
    public int countSameDayPairs(File outputDir) {
        if (outputDir == null || !outputDir.isDirectory()) return 0;
        String aajjj = String.format("%02d%03d", year % 100, julianDay);
        String prefix = indexPattern.substring(0, indexPattern.indexOf("[AAJJJ]") >= 0
                ? indexPattern.indexOf("[AAJJJ]") : 0);
        String marker = indexPattern.indexOf("[AAJJJ]") >= 0 ? (prefix + aajjj) : aajjj;
        String[] names = outputDir.list();
        if (names == null) return 0;
        int n = 0;
        for (int i = 0; i < names.length; i++) if (names[i].startsWith(marker)) n++;
        return n;
    }

    /** For the step log: what the naming will produce, without consuming a slot. */
    public List<String> describe() {
        List<String> out = new ArrayList<String>();
        out.add("julian day " + julianDay + " of " + year + " (" + String.format("%02d%03d", year % 100, julianDay) + ")");
        out.add("clock starts at " + hhmmss(totalSeconds) + " and advances 60s per batch");
        out.add(filesPerJulianDate == Integer.MAX_VALUE
                ? "no per-day pair limit" : (filesPerJulianDate + " pair(s) per julian day"));
        return out;
    }
}
