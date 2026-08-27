package com.legalarchive.orchestrator.tiff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a scan found.
 *
 * <b>By count and by bytes, always both.</b> A million small files already in G4 and ten thousand
 * large uncompressed ones read as "99% compressed" by count and as the opposite by bytes. The byte
 * column is the one that decides whether recompression is worth building; the count column on its own
 * is misleading in a way that is easy not to notice, so neither is ever reported alone.
 *
 * <b>This class renders lines; it does not write them.</b> The package carries no write API so that
 * the scan is read-only by construction, the way {@code elarcheck} is, and the caller in
 * {@code InternalSteps} puts the lines on disk.
 */
public final class TiffScanReport {

    /** One row of the histogram: a compression code, or the pseudo-code for a file that disagrees. */
    public static final class Row {
        public final String label;
        public long files, pages, bytes, g4EligibleFiles, g4EligibleBytes;
        Row(String label) { this.label = label; }
    }

    /** How the sample was drawn. A number from this scan is meaningless without it. */
    public String scanOrder = "RESERVOIR";
    public long   sampleSeed;
    public String directory = "";
    public boolean recursive;
    public long maxFilesScanned;

    public long filesEnumerated;      // entries the walk saw
    public long filesOpened;          // files actually parsed - the denominator
    public long bytesScanned;

    public long filesTiff, filesBigTiff, filesNotTiff, filesTruncated, filesIfdLoop, filesUnreadable;
    public long filesMixedCompression;

    public long filesAlreadyG4, bytesAlreadyG4;
    public long filesG4Eligible, bytesG4Eligible;

    private final Map<String, Row> rows = new LinkedHashMap<String, Row>();

    Row row(String label) {
        Row r = rows.get(label);
        if (r == null) { r = new Row(label); rows.put(label, r); }
        return r;
    }

    public List<Row> rows() { return new ArrayList<Row>(rows.values()); }

    /**
     * Every file opened landed in exactly one outcome. The cheapest assertion that the scan did what
     * it claims, and the first thing to check when a number looks wrong.
     */
    public boolean outcomesSumToFilesOpened() {
        return filesTiff + filesBigTiff + filesNotTiff + filesTruncated + filesIfdLoop + filesUnreadable
                == filesOpened;
    }

    public Map<String, String> asVars() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("filesEnumerated", String.valueOf(filesEnumerated));
        m.put("filesOpened", String.valueOf(filesOpened));
        m.put("bytesScanned", String.valueOf(bytesScanned));
        m.put("filesTiff", String.valueOf(filesTiff));
        m.put("filesBigTiff", String.valueOf(filesBigTiff));
        m.put("filesNotTiff", String.valueOf(filesNotTiff));
        m.put("filesTruncated", String.valueOf(filesTruncated));
        m.put("filesIfdLoop", String.valueOf(filesIfdLoop));
        m.put("filesUnreadable", String.valueOf(filesUnreadable));
        m.put("filesMixedCompression", String.valueOf(filesMixedCompression));
        m.put("filesAlreadyG4", String.valueOf(filesAlreadyG4));
        m.put("bytesAlreadyG4", String.valueOf(bytesAlreadyG4));
        m.put("filesG4Eligible", String.valueOf(filesG4Eligible));
        m.put("bytesG4Eligible", String.valueOf(bytesG4Eligible));
        m.put("scanOrder", scanOrder);
        m.put("sampleSeed", String.valueOf(sampleSeed));
        return m;
    }

    public String summary() {
        return "tiffcompress: enumerated " + filesEnumerated + ", opened " + filesOpened
                + " (" + scanOrder + ", seed " + sampleSeed + "), " + bytesScanned + " byte(s); "
                + filesAlreadyG4 + " already G4 (" + bytesAlreadyG4 + " byte(s)), "
                + filesG4Eligible + " could be (" + bytesG4Eligible + " byte(s))";
    }

    /**
     * The report as CSV lines, semicolon separated to match the rest of the platform. The header block
     * carries the sampling method, because a proportion quoted without it is not a proportion of
     * anything.
     */
    public List<String> csvLines() {
        List<String> out = new ArrayList<String>();
        out.add("# directory;" + directory);
        out.add("# recursive;" + recursive);
        out.add("# scanOrder;" + scanOrder);
        out.add("# sampleSeed;" + sampleSeed);
        out.add("# maxFilesScanned;" + maxFilesScanned);
        out.add("# filesEnumerated;" + filesEnumerated);
        out.add("# filesOpened;" + filesOpened);
        out.add("# bytesScanned;" + bytesScanned);
        out.add("# outcomesSumToFilesOpened;" + outcomesSumToFilesOpened());
        out.add("COMPRESSION;FILES;PAGES;BYTES;PCT_FILES;PCT_BYTES;G4_ELIGIBLE_FILES;G4_ELIGIBLE_BYTES");
        List<Row> rs = rows();
        for (int i = 0; i < rs.size(); i++) {
            Row r = rs.get(i);
            out.add(r.label + ";" + r.files + ";" + r.pages + ";" + r.bytes + ";"
                    + pct(r.files, filesOpened) + ";" + pct(r.bytes, bytesScanned) + ";"
                    + r.g4EligibleFiles + ";" + r.g4EligibleBytes);
        }
        return out;
    }

    /** One decimal place, and a dash rather than a zero when there is nothing to divide by. */
    private static String pct(long part, long whole) {
        if (whole <= 0) return "-";
        return String.valueOf(Math.round(part * 1000.0 / whole) / 10.0);
    }
}
