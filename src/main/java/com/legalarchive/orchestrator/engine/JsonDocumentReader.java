package com.legalarchive.orchestrator.engine;

import java.io.File;

/**
 * Reads one JSON file into the {@code Map} / {@code List} / {@code BigDecimal} / {@code String} /
 * {@code Boolean} / {@code null} tree that {@code com.legalarchive.orchestrator.json2csv} walks.
 *
 * <h3>Why this class is HERE and not in the json2csv package</h3>
 *
 * The specification put it in json2csv, "the one class where Jackson appears". Writing it proved that
 * wrong. Maven Central is unreachable from the sandbox, so a json2csv package containing a Jackson
 * import could not be compiled there at all — and compiling and running that package outside the
 * application is the entire property the design was built around. One class would have cost the other
 * eleven their test bench.
 *
 * <p>So the seam moved one step out: json2csv stays JDK-only and fully exercisable, and the Jackson
 * call sits here in {@code engine}, where Jackson already lives. Nothing else about the design
 * changes — this is still the only class that knows what parsed the tree.
 *
 * <h3>Why a private ObjectMapper</h3>
 *
 * {@code InternalSteps.jsonMapper} is shared by four other call sites: the dataschema reader, the
 * displayschema reader and two more. {@code USE_BIG_DECIMAL_FOR_FLOATS} is needed here so that
 * {@code 1.10} keeps its scale and reaches the CSV as {@code 1.10} rather than {@code 1.1} — and
 * enabling it on the shared instance would silently change how every one of those four reads its
 * numbers. A second mapper costs one object and changes nothing outside this executor.
 */
public final class JsonDocumentReader {

    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final long maxBytes;
    private final String charset;   // null = let Jackson auto-detect

    /**
     * @param maxFileMB refuse a file larger than this, in MB; 0 or less disables the guard
     * @param charset an explicit input charset, or null/empty for auto-detection
     */
    public JsonDocumentReader(int maxFileMB, String charset) {
        this.mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        this.mapper.enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.maxBytes = maxFileMB > 0 ? (long) maxFileMB * 1024L * 1024L : 0L;
        this.charset = (charset == null || charset.trim().isEmpty()) ? null : charset.trim();
    }

    /**
     * @throws java.io.IOException when the file is malformed; the caller's {@code onBadFile} decides
     * @throws IllegalStateException when the file is over the size guard — <b>checked before the file
     *         is read</b>, so the message names the size and the limit rather than arriving as an
     *         OutOfMemoryError halfway through a delivery
     */
    public Object read(File f) throws Exception {
        long len = f.length();
        if (maxBytes > 0 && len > maxBytes) {
            throw new IllegalStateException("file is " + (len / (1024L * 1024L)) + " MB, over the "
                    + (maxBytes / (1024L * 1024L)) + " MB limit (maxFileMB)");
        }
        if (charset == null) {
            // JSON is UTF-8 by specification and Jackson auto-detects UTF-8/16/32 from the BOM.
            return mapper.readValue(f, Object.class);
        }
        byte[] bytes = readAllBytes(f);
        return mapper.readValue(new String(bytes, java.nio.charset.Charset.forName(charset)), Object.class);
    }

    private static byte[] readAllBytes(File f) throws Exception {
        java.io.InputStream in = new java.io.FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }
    }
}
